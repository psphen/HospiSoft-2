package com.hospisoft.service;

import com.hospisoft.dao.CitaQuery;
import com.hospisoft.dao.CitaRepository;
import com.hospisoft.dao.MedicoRepository;
import com.hospisoft.dao.PacienteRepository;
import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Cita use cases (phase 4: scheduling, rescheduling, cancelling, agenda).
 *
 * <p>Rules: a cita occupies [fecha_hora_programada, + {@link #SLOT_MINUTES}); it must start at or
 * after {@link #WORK_START} and end at or before {@link #WORK_END}, within {@link #MAX_HORIZON_DAYS}
 * from today. A medico (and a paciente) may not have two non-cancelled citas whose intervals
 * intersect; adjacent slots are fine. New citas are always PROGRAMADA and may not be in the past.
 * Only PROGRAMADA citas can be rescheduled or cancelled. The row locks (medico first, then
 * paciente, {@code FOR NO KEY UPDATE}), the active re-checks, the overlap checks and the write
 * share one transaction. When a {@code fecha} filter is given the {@code rango} preset is ignored.
 *
 * <p>Roles: ADMIN full; MEDICO read-only on own citas and own agenda; PACIENTE read-only on own
 * citas. Out-of-scope reads answer NOT_FOUND.
 */
public final class CitaService {

    public static final int SLOT_MINUTES = 30;
    public static final LocalTime WORK_START = LocalTime.of(7, 0);
    public static final LocalTime WORK_END = LocalTime.of(19, 0);
    public static final int MAX_HORIZON_DAYS = 365;
    static final int MINUTE_STEP = 5;
    static final int MAX_MOTIVO = 200;

    static final String MEDICO_OVERLAP = "El médico ya tiene una cita en ese horario";
    static final String PACIENTE_OVERLAP = "El paciente ya tiene una cita en ese horario";
    static final String PAST = "La cita no puede estar en el pasado";
    static final String TOO_FAR = "La fecha no puede superar un año";
    static final String OUTSIDE_HOURS = "Fuera del horario de atención (07:00 a 19:00)";
    static final String NOT_PROGRAMADA = "Solo se pueden modificar citas programadas";
    static final String BAD_DATE = "Formato de fecha inválido (AAAA-MM-DD)";
    static final String BAD_TIME = "Formato de hora inválido (HH:MM)";
    static final String BAD_MINUTE = "La hora debe estar en intervalos de " + MINUTE_STEP + " minutos";
    static final String MEDICO_NOT_FOUND = "Médico no encontrado";
    static final String MEDICO_INACTIVE = "El médico está inactivo";
    static final String PACIENTE_NOT_FOUND = "Paciente no encontrado";
    static final String PACIENTE_INACTIVE = "El paciente está inactivo";

    private static final String ISO_DATE = "\\d{4}-\\d{2}-\\d{2}";

    private final CitaRepository citas;
    private final MedicoRepository medicos;
    private final PacienteRepository pacientes;
    private final Access access;
    private final Clock clock;
    private final AppZone zone;

    public CitaService(CitaRepository citas, MedicoRepository medicos, PacienteRepository pacientes,
                       Supplier<Optional<SessionContext>> session, Clock clock, AppZone zone) {
        this.citas = citas;
        this.medicos = medicos;
        this.pacientes = pacientes;
        this.access = new Access(session);
        this.clock = clock;
        this.zone = zone;
    }

    public AppZone zone() {
        return zone;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone.zone()));
    }

    // ------------------------------------------------------------ reads

    public Page<CitaView> list(CitaFilter filter, int page, int pageSize) {
        SessionContext session = access.require(Rol.ADMIN, Rol.MEDICO, Rol.PACIENTE);
        CitaFilter f = filter == null ? CitaFilter.NONE : filter;
        Validation.checkFilterText(f.texto());
        Long idMedico = f.idMedico();
        Long idPaciente = null;
        if (session.rol() == Rol.MEDICO) {
            idMedico = referenceOf(session);
        } else if (session.rol() == Rol.PACIENTE) {
            idPaciente = referenceOf(session);
        }
        // an explicit day takes precedence over the range preset
        OffsetDateTime[] range = f.fecha() != null ? new OffsetDateTime[] {null, null} : rangeBounds(f.rango());
        CitaQuery query = new CitaQuery(f.texto(),
            f.fecha() == null ? null : zone.dayStart(f.fecha()),
            f.fecha() == null ? null : zone.dayEnd(f.fecha()),
            f.estado(), idMedico, idPaciente, range[0], range[1]);
        return citas.findPage(query, page, pageSize);
    }

    public CitaView get(long id) {
        SessionContext session = access.require(Rol.ADMIN, Rol.MEDICO, Rol.PACIENTE);
        CitaView view = citas.findById(id).orElseThrow(() -> new NotFoundException("Cita " + id + " not found"));
        if (!inScope(session, view)) {
            throw new NotFoundException("Cita " + id + " not found");
        }
        return view;
    }

    /** @param idMedico null means "the session's own medico" (MEDICO only) */
    public Agenda agenda(Long idMedico, LocalDate fecha) {
        SessionContext session = access.require(Rol.ADMIN, Rol.MEDICO);
        long medico;
        if (session.rol() == Rol.MEDICO) {
            long own = referenceOf(session);
            if (idMedico != null && idMedico != own) {
                throw new PermissionException("MEDICO may only see its own agenda");
            }
            medico = own;
        } else {
            if (idMedico == null) {
                throw new ValidationException(Map.of("idMedico", Validation.REQUIRED));
            }
            medico = idMedico;
            if (medicos.findById(medico).isEmpty()) {
                throw new NotFoundException("Medico " + medico + " not found");
            }
        }
        LocalDate day = fecha == null ? today() : fecha;
        List<CitaView> ofDay = citas.findByMedicoAndDay(medico, zone.dayStart(day), zone.dayEnd(day));
        TreeMap<LocalTime, List<CitaView>> byTime = new TreeMap<>();
        for (LocalTime t = WORK_START; t.isBefore(WORK_END); t = t.plusMinutes(SLOT_MINUTES)) {
            byTime.put(t, new ArrayList<>());
        }
        for (CitaView view : ofDay) {
            if (view.cita().estado() == EstadoCita.CANCELADA) {
                continue; // a cancelled cita frees its slot
            }
            LocalTime time = zone.timeOf(view.cita().fechaHoraProgramada());
            LocalTime gridSlot = gridSlotOf(time);
            LocalTime key = gridSlot != null ? gridSlot : time; // outside working hours: its own time
            byTime.computeIfAbsent(key, k -> new ArrayList<>()).add(view);
        }
        List<Agenda.Slot> slots = new ArrayList<>(byTime.size());
        byTime.forEach((time, views) -> slots.add(new Agenda.Slot(time, views)));
        return new Agenda(medico, day, slots);
    }

    /** The working-hours grid slot whose [slot, slot + SLOT) contains {@code time}; null outside hours. */
    private static LocalTime gridSlotOf(LocalTime time) {
        if (time.isBefore(WORK_START) || !time.isBefore(WORK_END)) {
            return null;
        }
        int minutesFromStart = (time.toSecondOfDay() - WORK_START.toSecondOfDay()) / 60;
        return WORK_START.plusMinutes((long) (minutesFromStart / SLOT_MINUTES) * SLOT_MINUTES);
    }

    public List<Medico> medicosForSelect() {
        access.require(Rol.ADMIN);
        return medicos.findActiveForSelect();
    }

    public List<Paciente> pacientesForSelect() {
        access.require(Rol.ADMIN);
        return pacientes.findActiveForSelect();
    }

    // ------------------------------------------------------------ writes

    public CitaView create(CitaInput input) {
        access.require(Rol.ADMIN);
        Scheduled s = validate(input);
        long id = citas.inTransaction(tx -> {
            lockAndCheckParties(tx, s);
            checkOverlaps(tx, s, null);
            return tx.insert(new Cita(null, s.idMedico, s.idPaciente, s.start, s.motivo, EstadoCita.PROGRAMADA,
                null, null, null));
        });
        return citas.findById(id).orElseThrow(() -> new NotFoundException("Cita " + id + " not found"));
    }

    public CitaView reschedule(long id, CitaInput input) {
        access.require(Rol.ADMIN);
        CitaView existing = citas.findById(id).orElseThrow(() -> new NotFoundException("Cita " + id + " not found"));
        requireProgramada(existing);
        Scheduled s = validate(input);
        citas.inTransaction(tx -> {
            lockAndCheckParties(tx, s);
            checkOverlaps(tx, s, id);
            tx.reschedule(id, s.idMedico, s.idPaciente, s.start, s.motivo);
            return null;
        });
        return citas.findById(id).orElseThrow(() -> new NotFoundException("Cita " + id + " not found"));
    }

    public CitaView cancel(long id) {
        access.require(Rol.ADMIN);
        CitaView existing = citas.findById(id).orElseThrow(() -> new NotFoundException("Cita " + id + " not found"));
        requireProgramada(existing);
        citas.cancel(id);
        return citas.findById(id).orElseThrow(() -> new NotFoundException("Cita " + id + " not found"));
    }

    // ------------------------------------------------------------ internals

    private record Scheduled(long idMedico, long idPaciente, OffsetDateTime start, OffsetDateTime end, String motivo) {
    }

    private static void requireProgramada(CitaView view) {
        if (view.cita().estado() != EstadoCita.PROGRAMADA) {
            throw new ValidationException(Map.of("estado", NOT_PROGRAMADA));
        }
    }

    /** Locks medico THEN paciente (global order) and re-checks existence and activity inside the transaction. */
    private static void lockAndCheckParties(CitaRepository.CitaTx tx, Scheduled s) {
        Optional<Boolean> medico = tx.lockMedico(s.idMedico);
        if (medico.isEmpty()) {
            throw new ValidationException(Map.of("idMedico", Validation.NOT_EXISTS));
        }
        if (!medico.get()) {
            throw new ValidationException(Map.of("idMedico", Validation.MUST_BE_ACTIVE));
        }
        Optional<Boolean> paciente = tx.lockPaciente(s.idPaciente);
        if (paciente.isEmpty()) {
            throw new ValidationException(Map.of("idPaciente", Validation.NOT_EXISTS));
        }
        if (!paciente.get()) {
            throw new ValidationException(Map.of("idPaciente", Validation.MUST_BE_ACTIVE));
        }
    }

    private void checkOverlaps(CitaRepository.CitaTx tx, Scheduled s, Long excludeId) {
        if (tx.existsOverlapForMedico(s.idMedico, s.start, s.end, excludeId)) {
            throw new ValidationException(Map.of("hora", MEDICO_OVERLAP));
        }
        if (tx.existsOverlapForPaciente(s.idPaciente, s.start, s.end, excludeId)) {
            throw new ValidationException(Map.of("hora", PACIENTE_OVERLAP));
        }
    }

    /** Fast-path validation outside the transaction; existence and activity are re-checked inside it. */
    private Scheduled validate(CitaInput in) {
        Validation v = new Validation();
        Long idMedico = in.idMedico();
        Long idPaciente = in.idPaciente();
        if (idMedico == null) {
            v.reject("idMedico", Validation.REQUIRED);
        } else {
            Optional<Medico> medico = medicos.findById(idMedico);
            if (medico.isEmpty()) {
                v.reject("idMedico", MEDICO_NOT_FOUND);
            } else if (!medico.get().activo()) {
                v.reject("idMedico", MEDICO_INACTIVE);
            }
        }
        if (idPaciente == null) {
            v.reject("idPaciente", Validation.REQUIRED);
        } else {
            Optional<Paciente> paciente = pacientes.findById(idPaciente);
            if (paciente.isEmpty()) {
                v.reject("idPaciente", PACIENTE_NOT_FOUND);
            } else if (!paciente.get().activo()) {
                v.reject("idPaciente", PACIENTE_INACTIVE);
            }
        }
        LocalDate date = null;
        String fecha = Validation.clean(in.fecha());
        if (fecha == null) {
            v.reject("fecha", Validation.REQUIRED);
        } else if (!fecha.matches(ISO_DATE)) {
            v.reject("fecha", BAD_DATE);
        } else {
            try {
                date = LocalDate.parse(fecha);
            } catch (DateTimeParseException ex) {
                v.reject("fecha", BAD_DATE);
            }
        }
        LocalTime time = null;
        String hora = Validation.clean(in.hora());
        if (hora == null) {
            v.reject("hora", Validation.REQUIRED);
        } else if (!hora.matches("\\d{2}:\\d{2}")) {
            v.reject("hora", BAD_TIME);
        } else {
            try {
                time = LocalTime.parse(hora);
                if (time.getMinute() % MINUTE_STEP != 0) {
                    v.reject("hora", BAD_MINUTE);
                } else if (time.isBefore(WORK_START) || time.plusMinutes(SLOT_MINUTES).isAfter(WORK_END)
                    || time.plusMinutes(SLOT_MINUTES).isBefore(time)) {
                    v.reject("hora", OUTSIDE_HOURS); // the last clause catches wrap-around past midnight
                }
            } catch (DateTimeParseException ex) {
                v.reject("hora", BAD_TIME);
            }
        }
        String motivo = Validation.clean(in.motivo());
        v.required("motivo", motivo);
        v.maxLength("motivo", motivo, MAX_MOTIVO);
        OffsetDateTime start = null;
        if (date != null && time != null && !v.errors().containsKey("hora")) {
            start = zone.at(date, time);
            if (start.isBefore(OffsetDateTime.now(clock))) {
                v.reject("fecha", PAST);
            } else if (date.isAfter(today().plusDays(MAX_HORIZON_DAYS))) {
                v.reject("fecha", TOO_FAR);
            }
        }
        v.throwIfInvalid();
        return new Scheduled(idMedico, idPaciente, start, start.plusMinutes(SLOT_MINUTES), motivo);
    }

    private OffsetDateTime[] rangeBounds(CitaRango rango) {
        if (rango == null || rango == CitaRango.TODAS) {
            return new OffsetDateTime[] {null, null};
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = today();
        return switch (rango) {
            case HOY -> new OffsetDateTime[] {zone.dayStart(today), zone.dayEnd(today)};
            case FUTURAS -> new OffsetDateTime[] {now, null};
            case PASADAS -> new OffsetDateTime[] {null, now};
            case HOY_Y_FUTURAS -> new OffsetDateTime[] {zone.dayStart(today), null};
            case TODAS -> new OffsetDateTime[] {null, null};
        };
    }

    /** Ids are boxed Longs: compare by value, never by reference (ids above 127 are not cached). */
    private static boolean inScope(SessionContext session, CitaView view) {
        return switch (session.rol()) {
            case ADMIN -> true;
            case MEDICO -> Objects.equals(session.idReferencia(), view.cita().idMedico());
            case PACIENTE -> Objects.equals(session.idReferencia(), view.cita().idPaciente());
        };
    }

    private static long referenceOf(SessionContext session) {
        if (session.idReferencia() == null) {
            throw new PermissionException(session.rol() + " session without a linked row");
        }
        return session.idReferencia();
    }
}
