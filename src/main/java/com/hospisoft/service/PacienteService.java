package com.hospisoft.service;

import com.hospisoft.dao.HasHistoryException;
import com.hospisoft.dao.PacienteFilter;
import com.hospisoft.dao.PacienteRepository;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Paciente use cases. ADMIN has full CRUD; MEDICO may only read the patients it has attended
 * (at least one non-cancelled cita) and gets NOT_FOUND, never FORBIDDEN, for anything out of
 * scope so it cannot probe which ids exist; PACIENTE has no access in this phase.
 */
public final class PacienteService {

    static final int MAX_DOCUMENTO = 20;
    static final int MAX_NOMBRES = 80;
    static final int MAX_APELLIDOS = 80;
    static final int MAX_TELEFONO = 20;
    static final int MAX_CORREO = 120;
    static final int MAX_DIRECCION = 160;
    static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);
    static final Set<String> SEXOS = Set.of("M", "F", "OTRO");

    static final String FUTURE_BIRTH_DATE = "La fecha de nacimiento no puede ser futura";
    static final String BIRTH_DATE_TOO_OLD = "La fecha de nacimiento no puede ser anterior a 1900";
    static final String INVALID_SEXO = "Valor inválido (M, F u OTRO)";

    private final PacienteRepository pacientes;
    private final Access access;
    private final Clock clock;

    public PacienteService(PacienteRepository pacientes, Supplier<Optional<SessionContext>> session) {
        this(pacientes, session, Clock.systemDefaultZone());
    }

    public PacienteService(PacienteRepository pacientes, Supplier<Optional<SessionContext>> session, Clock clock) {
        this.pacientes = pacientes;
        this.access = new Access(session);
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Page<Paciente> list(PacienteFilter filter, int page, int pageSize) {
        SessionContext session = access.require(Rol.ADMIN, Rol.MEDICO);
        PacienteFilter safe = filter == null ? PacienteFilter.NONE : filter;
        Validation.checkFilterText(safe.texto());
        if (session.rol() == Rol.MEDICO) {
            return pacientes.findPageAttendedBy(medicoIdOf(session), safe, page, pageSize);
        }
        return pacientes.findPage(safe, page, pageSize);
    }

    public Paciente get(long id) {
        SessionContext session = access.require(Rol.ADMIN, Rol.MEDICO);
        Optional<Paciente> found = session.rol() == Rol.MEDICO
            ? pacientes.findByIdAttendedBy(id, medicoIdOf(session))
            : pacientes.findById(id);
        return found.orElseThrow(() -> new NotFoundException("Paciente " + id + " not found"));
    }

    /** Insert when the id is null or 0, update otherwise, with {@code activo} taken from the input. */
    public Paciente save(Paciente input) {
        return save(input, input.activo());
    }

    /**
     * @param activo null keeps the stored value on update (true on insert); otherwise the new value
     * @return the stored row
     */
    public Paciente save(Paciente input, Boolean activo) {
        access.require(Rol.ADMIN);
        Paciente clean = normalize(input);
        if (clean.id() == null) {
            clean = withActivo(clean, activo == null || activo);
            validate(clean);
            long id = pacientes.insert(clean);
            return withId(clean, id);
        }
        long id = clean.id();
        Paciente stored = pacientes.findById(id)
            .orElseThrow(() -> new NotFoundException("Paciente " + id + " not found"));
        clean = withActivo(clean, activo == null ? stored.activo() : activo);
        validate(clean);
        pacientes.update(clean);
        return clean;
    }

    public DeleteOutcome delete(long id) {
        access.require(Rol.ADMIN);
        pacientes.findById(id).orElseThrow(() -> new NotFoundException("Paciente " + id + " not found"));
        if (pacientes.hasAssociatedHistory(id)) {
            pacientes.setActivo(id, false);
            return DeleteOutcome.DEACTIVATED;
        }
        try {
            pacientes.delete(id);
            return DeleteOutcome.DELETED;
        } catch (HasHistoryException ex) {
            // history appeared between the check and the delete: deactivate instead
            pacientes.setActivo(id, false);
            return DeleteOutcome.DEACTIVATED;
        }
    }

    private static long medicoIdOf(SessionContext session) {
        if (session.idReferencia() == null) {
            throw new PermissionException("MEDICO session without a linked medico");
        }
        return session.idReferencia();
    }

    private static Paciente normalize(Paciente p) {
        Long id = p.id() == null || p.id() == 0 ? null : p.id();
        String sexo = Validation.clean(p.sexo());
        return new Paciente(id, Validation.clean(p.documento()), Validation.clean(p.nombres()),
            Validation.clean(p.apellidos()), p.fechaNacimiento(),
            sexo == null ? null : sexo.toUpperCase(Locale.ROOT),
            Validation.clean(p.telefono()), Validation.clean(p.correo()), Validation.clean(p.direccion()),
            p.activo());
    }

    private void validate(Paciente p) {
        Validation v = new Validation();
        v.required("documento", p.documento());
        v.required("nombres", p.nombres());
        v.required("apellidos", p.apellidos());
        v.required("sexo", p.sexo());
        if (p.fechaNacimiento() == null) {
            v.reject("fechaNacimiento", Validation.REQUIRED);
        } else if (p.fechaNacimiento().isAfter(today())) {
            v.reject("fechaNacimiento", FUTURE_BIRTH_DATE);
        } else if (p.fechaNacimiento().isBefore(MIN_BIRTH_DATE)) {
            v.reject("fechaNacimiento", BIRTH_DATE_TOO_OLD);
        }
        if (p.sexo() != null && !SEXOS.contains(p.sexo())) {
            v.reject("sexo", INVALID_SEXO);
        }
        v.maxLength("documento", p.documento(), MAX_DOCUMENTO);
        v.maxLength("nombres", p.nombres(), MAX_NOMBRES);
        v.maxLength("apellidos", p.apellidos(), MAX_APELLIDOS);
        v.maxLength("telefono", p.telefono(), MAX_TELEFONO);
        v.maxLength("correo", p.correo(), MAX_CORREO);
        v.maxLength("direccion", p.direccion(), MAX_DIRECCION);
        v.email("correo", p.correo());
        if (p.documento() != null && !v.errors().containsKey("documento")) {
            Optional<Paciente> other = pacientes.findByDocumento(p.documento());
            if (other.isPresent() && !other.get().id().equals(p.id())) {
                v.reject("documento", Validation.DUPLICATE_DOCUMENTO);
            }
        }
        v.throwIfInvalid();
    }

    private static Paciente withActivo(Paciente p, boolean activo) {
        return new Paciente(p.id(), p.documento(), p.nombres(), p.apellidos(), p.fechaNacimiento(), p.sexo(),
            p.telefono(), p.correo(), p.direccion(), activo);
    }

    private static Paciente withId(Paciente p, long id) {
        return new Paciente(id, p.documento(), p.nombres(), p.apellidos(), p.fechaNacimiento(), p.sexo(),
            p.telefono(), p.correo(), p.direccion(), p.activo());
    }
}
