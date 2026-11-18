package com.hospisoft.service;

import com.hospisoft.dao.AtencionRepository;
import com.hospisoft.dao.CitaRepository;
import com.hospisoft.dao.HistoriaQuery;
import com.hospisoft.dao.MedicamentoRepository;
import com.hospisoft.dao.PacienteRepository;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Page;
import com.hospisoft.model.RecetaView;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class AtencionService {
    static final int MAX_DIAGNOSTICO = 500;
    static final int MAX_TRATAMIENTO = 500;
    static final int MAX_NOTAS = 1000;
    static final int MAX_OBSERVACIONES = 500;
    static final int MAX_DOSIS = 100;
    static final int MAX_DURACION = 100;
    static final int MAX_OBSERVACION_NO_DISPONIBLE = 200;
    static final int MAX_CANTIDAD = 9999;
    static final int MAX_RECETAS = 50;

    public static final String ALREADY_EN_CURSO = "Ya tiene una atención en curso";
    static final String NOT_PROGRAMADA = "La cita ya no está programada";
    static final String NOT_EN_CURSO = "La atención no está en curso";
    static final String OBSERVACION_REQUIRED = "Indique por qué no se entrega";
    static final String CANTIDAD_RANGE = "Debe estar entre 1 y " + MAX_CANTIDAD;
    static final String TOO_MANY_RECETAS = "Máximo " + MAX_RECETAS + " medicamentos por atención";
    static final String STOCK_RACE = "El stock cambió durante la atención. Intente finalizar nuevamente.";
    static final String MEDICO_UNAVAILABLE = "La cuenta del médico no está activa";
    public static final String ALREADY_CLOSED = "La atención ya fue finalizada";

    private final AtencionRepository atenciones;
    private final CitaRepository citas;
    private final MedicamentoRepository medicamentos;
    private final PacienteRepository pacientes;
    private final Access access;
    private final Clock clock;
    private final AppZone zone;
    private final AuditLog audit;

    public AtencionService(AtencionRepository atenciones, CitaRepository citas, MedicamentoRepository medicamentos,
                           PacienteRepository pacientes, Supplier<Optional<SessionContext>> session,
                           Clock clock, AppZone zone, AuditLog audit) {
        this.atenciones = atenciones;
        this.citas = citas;
        this.medicamentos = medicamentos;
        this.pacientes = pacientes;
        this.access = new Access(session);
        this.clock = clock;
        this.zone = zone;
        this.audit = audit;
    }

    public AppZone zone() {
        return zone;
    }

    public CitaView iniciar(long idCita) {
        SessionContext current = access.require(Rol.MEDICO);
        long idMedico = referenceOf(current);
        String paciente = citas.findById(idCita)
            .filter(view -> view.cita().idMedico() == idMedico)
            .map(view -> " - " + view.pacienteNombre())
            .orElse("");
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
        audit.record(AccionAuditoria.INICIAR_ATENCION, ModuloAuditoria.ATENCION, idCita,
            "Atención iniciada - cita #" + idCita + paciente);
        return reloadCita(idCita);
    }

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
            throw new NotFoundException("Cita " + idCita + " not found");
        }
        long idPaciente = cita.cita().idPaciente();

        Closed closed = atenciones.inTransaction(tx -> {
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
                    throw new ConflictException(
                        "Stock of medicamento " + delivery.line.idMedicamento + " changed while locked", STOCK_RACE);
                }
                tx.insertReceta(historia, delivery.line.idMedicamento, delivery.line.cantidad, delivery.line.dosis,
                    delivery.line.duracion, delivery.deliver, delivery.deliver ? null : delivery.observacion);
            }
            int undelivered = (int) deliveries.stream().filter(d -> !d.deliver).count();
            return new Closed(historia, deliveries.size(), undelivered);
        });
        long idHistoria = closed.idHistoria;
        audit.record(AccionAuditoria.FINALIZAR_ATENCION, ModuloAuditoria.ATENCION, idCita,
            "Atención finalizada - cita #" + idCita + " - " + cita.pacienteNombre() + " - historia #" + idHistoria
                + " - " + (closed.lines == 0
                    ? "sin medicamentos"
                    : closed.lines + " medicamento(s), " + closed.undelivered + " sin entregar"));

        HistoriaView stored = atenciones.findHistoriaById(idHistoria)
            .orElseThrow(() -> new NotFoundException("Historia " + idHistoria + " not found"));
        return new Atencion(idHistoria, reloadCita(idCita), stored.recetas());
    }

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
            default -> {}
        }
        return atenciones.findHistoriasPage(new HistoriaQuery(idPaciente, idMedico, null), page, pageSize);
    }

    public Page<RecetaView> misRecetas(int page, int pageSize) {
        SessionContext current = access.require(Rol.PACIENTE);
        return atenciones.findRecetasPage(referenceOf(current), page, pageSize);
    }

    public List<Medicamento> medicamentosParaRecetar() {
        access.require(Rol.MEDICO, Rol.ADMIN);
        return medicamentos.findAllActive();
    }

    private record Line(long idMedicamento, int cantidad, String dosis, String duracion, String observacion,
                        int index) {
    }

    private record Clean(String diagnostico, String tratamiento, String notas, String observaciones, List<Line> lines) {
    }

    private record Delivery(Line line, boolean deliver, String observacion) {
    }

    private record Closed(long idHistoria, int lines, int undelivered) {
    }

    private CitaView reloadCita(long idCita) {
        return citas.findById(idCita).orElseThrow(() -> new NotFoundException("Cita " + idCita + " not found"));
    }

    private static void requireActiveMedico(Optional<Boolean> locked) {
        if (locked.isEmpty() || !locked.get()) {
            throw new PermissionException(MEDICO_UNAVAILABLE);
        }
    }

    private static RuntimeException stateFailure(Optional<EstadoCita> estado, long idCita, String message) {
        if (estado.isEmpty()) {
            return new NotFoundException("Cita " + idCita + " not found");
        }
        return new ConflictException("Cita " + idCita + " is " + estado.get(), message);
    }

    private static RuntimeException closeFailure(Optional<EstadoCita> estado, long idCita) {
        if (estado.isPresent() && estado.get() == EstadoCita.FINALIZADA) {
            return new ConflictException("Cita " + idCita + " is FINALIZADA", ALREADY_CLOSED);
        }
        return stateFailure(estado, idCita, NOT_EN_CURSO);
    }

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
            v.throwIfInvalid();
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
