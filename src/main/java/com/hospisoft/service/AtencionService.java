package com.hospisoft.service;

import com.hospisoft.dao.AtencionRepository;
import com.hospisoft.dao.CitaRepository;
import com.hospisoft.dao.HistoriaQuery;
import com.hospisoft.dao.MedicamentoRepository;
import com.hospisoft.dao.PacienteRepository;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Page;
import com.hospisoft.model.RecetaView;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Attention lifecycle, clinical record and prescriptions (phase 5).
 *
 * <p>Only a MEDICO starts and closes its own citas, and only one cita may be EN_CURSO per medico.
 * Closing writes the cita state, the historia and every receta, and decrements the stock of the
 * delivered lines, all in one transaction: the medico row is locked first and then each prescribed
 * medicamento in ascending id order (deadlock-avoidance order). A line whose locked stock is below
 * the requested quantity - or whose medicamento is inactive - is stored as not delivered and then
 * requires {@code observacionNoDisponible}.
 *
 * <p>The historia is immutable (database triggers reject UPDATE/DELETE/TRUNCATE) and there is at
 * most one per cita: a second closing surfaces as CONFLICT.
 *
 * <p>Reads: ADMIN sees everything; MEDICO the historial of its own patients (a non-cancelled cita
 * with them); PACIENTE only its own historias and recetas. Out-of-scope reads answer NOT_FOUND.
 */
public final class AtencionService {

    /** historia_clinica.diagnostico is TEXT (unbounded): this is the business limit. */
    static final int MAX_DIAGNOSTICO = 500;
    /** historia_clinica.tratamiento is TEXT (unbounded) and nullable; required by business rule. */
    static final int MAX_TRATAMIENTO = 500;
    /** historia_clinica.notas is TEXT (unbounded). */
    static final int MAX_NOTAS = 1000;
    /** cita.observaciones is TEXT (unbounded). */
    static final int MAX_OBSERVACIONES = 500;
    static final int MAX_DOSIS = 100;
    static final int MAX_DURACION = 100;
    /** receta.observacion_no_disponible is VARCHAR(200). */
    static final int MAX_OBSERVACION_NO_DISPONIBLE = 200;
    static final int MAX_CANTIDAD = 9999;
    /** Upper bound of prescribed lines per attention (keeps one transaction bounded). */
    static final int MAX_RECETAS = 50;

    public static final String ALREADY_EN_CURSO = "Ya tiene una atención en curso";
    static final String NOT_PROGRAMADA = "La cita ya no está programada";
    static final String NOT_EN_CURSO = "La atención no está en curso";
    static final String OBSERVACION_REQUIRED = "Indique por qué no se entrega";
    static final String CANTIDAD_RANGE = "Debe estar entre 1 y " + MAX_CANTIDAD;
    static final String TOO_MANY_RECETAS = "Máximo " + MAX_RECETAS + " medicamentos por atención";
    static final String STOCK_RACE = "El stock cambió durante la atención. Intente finalizar nuevamente.";
    static final String MEDICO_UNAVAILABLE = "La cuenta del médico no está activa";
    /** Shown when the cita was already closed (second close, or a historia already exists). */
    public static final String ALREADY_CLOSED = "La atención ya fue finalizada";

    private final AtencionRepository atenciones;
    private final CitaRepository citas;
    private final MedicamentoRepository medicamentos;
    private final PacienteRepository pacientes;
    private final Access access;
    private final Clock clock;
    private final AppZone zone;

    public AtencionService(AtencionRepository atenciones, CitaRepository citas, MedicamentoRepository medicamentos,
                           PacienteRepository pacientes, Supplier<Optional<SessionContext>> session,
                           Clock clock, AppZone zone) {
        this.atenciones = atenciones;
        this.citas = citas;
        this.medicamentos = medicamentos;
        this.pacientes = pacientes;
        this.access = new Access(session);
        this.clock = clock;
        this.zone = zone;
    }

    public AppZone zone() {
        return zone;
    }

    // ------------------------------------------------------------ lifecycle

    /** PROGRAMADA -> EN_CURSO on one of the session medico's own citas. */
    public CitaView iniciar(long idCita) {
        SessionContext current = access.require(Rol.MEDICO);
        long idMedico = referenceOf(current);
        atenciones.inTransaction(tx -> {
            requireActiveMedico(tx.lockMedico(idMedico));
            if (tx.existsEnCurso(idMedico, idCita)) {
                throw new ValidationException(Map.of("estado", ALREADY_EN_CURSO));
            }
            if (tx.iniciar(idCita, idMedico) == 0) {
                throw stateFailure(tx.estadoOfOwnCita(idCita, idMedico), idCita, NOT_PROGRAMADA);
            }
            return null;
        });
        return reloadCita(idCita);
    }

    /** EN_CURSO -> FINALIZADA together with the historia, its recetas and the stock decrements. */
    public Atencion finalizar(AtencionInput input) {
        SessionContext current = access.require(Rol.MEDICO);
        long idMedico = referenceOf(current);
        if (input == null || input.idCita() == null) {
            throw new ValidationException(Map.of("idCita", Validation.REQUIRED));
        }
        long idCita = input.idCita();
        Clean clean = validate(input);
        CitaView cita = citas.findById(idCita)
            .orElseThrow(() -> new NotFoundException("Cita " + idCita + " not found"));
        if (cita.cita().idMedico() != idMedico) {
            throw new NotFoundException("Cita " + idCita + " not found"); // never reveal other medicos' citas
        }
        long idPaciente = cita.cita().idPaciente();

        long idHistoria = atenciones.inTransaction(tx -> {
            requireActiveMedico(tx.lockMedico(idMedico));
            Map<Long, AtencionRepository.MedicamentoLock> locked = lockMedicamentos(tx, clean.lines);
            List<Delivery> deliveries = decideDeliveries(clean.lines, locked);

            if (tx.finalizar(idCita, idMedico, clean.observaciones) == 0) {
                throw closeFailure(tx.estadoOfOwnCita(idCita, idMedico), idCita);
            }
            long historia = tx.insertHistoria(idCita, idPaciente, idMedico, clean.diagnostico, clean.tratamiento,
                clean.notas);
            for (Delivery delivery : deliveries) {
                if (delivery.deliver && tx.decrementStock(delivery.line.idMedicamento, delivery.line.cantidad) == 0) {
                    // the row is locked, so this cannot happen: an invariant broke, roll everything back
                    throw new ConflictException(
                        "Stock of medicamento " + delivery.line.idMedicamento + " changed while locked", STOCK_RACE);
                }
                tx.insertReceta(historia, delivery.line.idMedicamento, delivery.line.cantidad, delivery.line.dosis,
                    delivery.line.duracion, delivery.deliver, delivery.deliver ? null : delivery.observacion);
            }
            return historia;
        });

        HistoriaView stored = atenciones.findHistoriaById(idHistoria)
            .orElseThrow(() -> new NotFoundException("Historia " + idHistoria + " not found"));
        return new Atencion(idHistoria, reloadCita(idCita), stored.recetas());
    }

    // ------------------------------------------------------------ reads

    /** The historia of a cita the caller may see. */
    public HistoriaView historiaDeCita(long idCita) {
        SessionContext current = access.require(Rol.ADMIN, Rol.MEDICO, Rol.PACIENTE);
        CitaView cita = citas.findById(idCita)
            .orElseThrow(() -> new NotFoundException("Cita " + idCita + " not found"));
        if (!citaInScope(current, cita)) {
            throw new NotFoundException("Cita " + idCita + " not found");
        }
        return atenciones.findHistoriaByCita(idCita)
            .orElseThrow(() -> new NotFoundException("Cita " + idCita + " has no historia"));
    }

    /**
     * Clinical history of a patient (newest first). ADMIN and the patient see every entry; a MEDICO
     * sees only the entries it wrote, so the list never shows a colleague's diagnosis that the
     * detail view ({@link #historiaDeCita(long)}, scoped to own citas) would refuse to open.
     */
    public Page<HistoriaView> historialPaciente(long idPaciente, int page, int pageSize) {
        SessionContext current = access.require(Rol.ADMIN, Rol.MEDICO, Rol.PACIENTE);
        Long idMedico = null;
        switch (current.rol()) {
            case MEDICO -> {
                long own = referenceOf(current);
                if (pacientes.findByIdAttendedBy(idPaciente, own).isEmpty()) {
                    throw new NotFoundException("Paciente " + idPaciente + " not found");
                }
                idMedico = own;
            }
            case PACIENTE -> {
                if (referenceOf(current) != idPaciente) {
                    throw new NotFoundException("Paciente " + idPaciente + " not found");
                }
            }
            default -> {
                // ADMIN sees every entry of every patient
            }
        }
        return atenciones.findHistoriasPage(new HistoriaQuery(idPaciente, idMedico, null), page, pageSize);
    }

    /** The session patient's own prescriptions, newest first. */
    public Page<RecetaView> misRecetas(int page, int pageSize) {
        SessionContext current = access.require(Rol.PACIENTE);
        return atenciones.findRecetasPage(referenceOf(current), page, pageSize);
    }

    /** Active medicamentos with their stock, for the prescription form. */
    public List<Medicamento> medicamentosParaRecetar() {
        access.require(Rol.MEDICO, Rol.ADMIN);
        return medicamentos.findAllActive();
    }

    // ------------------------------------------------------------ internals

    private record Line(long idMedicamento, int cantidad, String dosis, String duracion, String observacion,
                        int index) {
    }

    private record Clean(String diagnostico, String tratamiento, String notas, String observaciones, List<Line> lines) {
    }

    private record Delivery(Line line, boolean deliver, String observacion) {
    }

    private CitaView reloadCita(long idCita) {
        return citas.findById(idCita).orElseThrow(() -> new NotFoundException("Cita " + idCita + " not found"));
    }

    private static void requireActiveMedico(Optional<Boolean> locked) {
        if (locked.isEmpty() || !locked.get()) {
            throw new PermissionException(MEDICO_UNAVAILABLE);
        }
    }

    /** 0 affected rows: missing or another medico's cita is NOT_FOUND, a wrong state is CONFLICT. */
    private static RuntimeException stateFailure(Optional<EstadoCita> estado, long idCita, String message) {
        if (estado.isEmpty()) {
            return new NotFoundException("Cita " + idCita + " not found");
        }
        return new ConflictException("Cita " + idCita + " is " + estado.get(), message);
    }

    /** Closing failed: an already finished cita gets its own message, any other state the generic one. */
    private static RuntimeException closeFailure(Optional<EstadoCita> estado, long idCita) {
        if (estado.isPresent() && estado.get() == EstadoCita.FINALIZADA) {
            return new ConflictException("Cita " + idCita + " is FINALIZADA", ALREADY_CLOSED);
        }
        return stateFailure(estado, idCita, NOT_EN_CURSO);
    }

    /** Locks every distinct medicamento in ASCENDING id order (deadlock-avoidance order). */
    private static Map<Long, AtencionRepository.MedicamentoLock> lockMedicamentos(
        AtencionRepository.AtencionTx tx, List<Line> lines) {
        Map<Long, AtencionRepository.MedicamentoLock> locked = new LinkedHashMap<>();
        List<Long> ids = lines.stream().map(Line::idMedicamento).distinct().sorted().toList();
        Map<String, String> missing = new LinkedHashMap<>();
        for (long id : ids) {
            Optional<AtencionRepository.MedicamentoLock> lock = tx.lockMedicamento(id);
            if (lock.isEmpty()) {
                lines.stream().filter(line -> line.idMedicamento == id)
                    .forEach(line -> missing.put(field(line.index, "idMedicamento"), Validation.NOT_EXISTS));
            } else {
                locked.put(id, lock.get());
            }
        }
        if (!missing.isEmpty()) {
            throw new ValidationException(missing);
        }
        return locked;
    }

    /**
     * Decides, with the locked stock, which lines are delivered. Several lines of the same
     * medicamento consume the stock cumulatively. A line that cannot be delivered needs an
     * observation.
     */
    private static List<Delivery> decideDeliveries(List<Line> lines,
                                                   Map<Long, AtencionRepository.MedicamentoLock> locked) {
        Map<Long, Integer> remaining = new HashMap<>();
        locked.forEach((id, lock) -> remaining.put(id, lock.stock()));
        List<Delivery> deliveries = new ArrayList<>(lines.size());
        Map<String, String> errors = new LinkedHashMap<>();
        for (Line line : lines) {
            AtencionRepository.MedicamentoLock lock = locked.get(line.idMedicamento);
            int left = remaining.getOrDefault(line.idMedicamento, 0);
            boolean deliver = lock.activo() && left >= line.cantidad;
            if (deliver) {
                remaining.put(line.idMedicamento, left - line.cantidad);
            } else if (line.observacion == null) {
                errors.put(field(line.index, "observacionNoDisponible"), OBSERVACION_REQUIRED);
            }
            deliveries.add(new Delivery(line, deliver, line.observacion));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return deliveries;
    }

    private static String field(int index, String name) {
        return "recetas[" + index + "]." + name;
    }

    private Clean validate(AtencionInput in) {
        Validation v = new Validation();
        String diagnostico = Validation.clean(in.diagnostico());
        String tratamiento = Validation.clean(in.tratamiento());
        String notas = Validation.clean(in.notas());
        String observaciones = Validation.clean(in.observaciones());
        v.required("diagnostico", diagnostico);
        v.required("tratamiento", tratamiento);
        v.maxLength("diagnostico", diagnostico, MAX_DIAGNOSTICO);
        v.maxLength("tratamiento", tratamiento, MAX_TRATAMIENTO);
        v.maxLength("notas", notas, MAX_NOTAS);
        v.maxLength("observaciones", observaciones, MAX_OBSERVACIONES);

        if (in.recetas().size() > MAX_RECETAS) {
            v.reject("recetas", TOO_MANY_RECETAS);
            v.throwIfInvalid(); // stop before the per-line lookups
        }
        List<Line> lines = new ArrayList<>(in.recetas().size());
        for (int i = 0; i < in.recetas().size(); i++) {
            AtencionInput.RecetaInput raw = in.recetas().get(i);
            Long idMedicamento = raw.idMedicamento();
            if (idMedicamento == null) {
                v.reject(field(i, "idMedicamento"), Validation.REQUIRED);
            } else if (medicamentos.findById(idMedicamento).isEmpty()) {
                v.reject(field(i, "idMedicamento"), Validation.NOT_EXISTS);
            }
            int cantidad = v.intInRange(field(i, "cantidad"), raw.cantidad(), 1, MAX_CANTIDAD, CANTIDAD_RANGE);
            String dosis = Validation.clean(raw.dosis());
            String duracion = Validation.clean(raw.duracion());
            String observacion = Validation.clean(raw.observacionNoDisponible());
            v.required(field(i, "dosis"), dosis);
            v.required(field(i, "duracion"), duracion);
            v.maxLength(field(i, "dosis"), dosis, MAX_DOSIS);
            v.maxLength(field(i, "duracion"), duracion, MAX_DURACION);
            v.maxLength(field(i, "observacionNoDisponible"), observacion, MAX_OBSERVACION_NO_DISPONIBLE);
            lines.add(new Line(idMedicamento == null ? 0 : idMedicamento, cantidad, dosis, duracion, observacion, i));
        }
        v.throwIfInvalid();
        return new Clean(diagnostico, tratamiento, notas, observaciones, lines);
    }

    /** Ids are boxed Longs: compare by value, never by reference (ids above 127 are not cached). */
    private static boolean citaInScope(SessionContext session, CitaView cita) {
        return switch (session.rol()) {
            case ADMIN -> true;
            case MEDICO -> Objects.equals(session.idReferencia(), cita.cita().idMedico());
            case PACIENTE -> Objects.equals(session.idReferencia(), cita.cita().idPaciente());
        };
    }

    private static long referenceOf(SessionContext session) {
        if (session.idReferencia() == null) {
            throw new PermissionException(session.rol() + " session without a linked row");
        }
        return session.idReferencia();
    }
}
