package com.hospisoft.service;

import com.hospisoft.dao.HasHistoryException;
import com.hospisoft.dao.MedicoFilter;
import com.hospisoft.dao.MedicoRepository;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Medico use cases. Only ADMIN may use this service in this phase. Input is trimmed and validated
 * against the schema limits; deleting a medico with history deactivates it instead.
 */
public final class MedicoService {

    static final int MAX_DOCUMENTO = 20;
    static final int MAX_NOMBRES = 80;
    static final int MAX_APELLIDOS = 80;
    static final int MAX_ESPECIALIDAD = 80;
    static final int MAX_TELEFONO = 20;
    static final int MAX_CORREO = 120;

    private final MedicoRepository medicos;
    private final Access access;

    public MedicoService(MedicoRepository medicos, Supplier<Optional<SessionContext>> session) {
        this.medicos = medicos;
        this.access = new Access(session);
    }

    public Page<Medico> list(MedicoFilter filter, int page, int pageSize) {
        access.require(Rol.ADMIN);
        MedicoFilter safe = filter == null ? MedicoFilter.NONE : filter;
        Validation.checkFilterText(safe.texto());
        return medicos.findPage(safe, page, pageSize);
    }

    public Medico get(long id) {
        access.require(Rol.ADMIN);
        return medicos.findById(id).orElseThrow(() -> new NotFoundException("Medico " + id + " not found"));
    }

    public List<String> especialidades() {
        access.require(Rol.ADMIN);
        return medicos.findEspecialidades();
    }

    /** Insert when the id is null or 0, update otherwise, with {@code activo} taken from the input. */
    public Medico save(Medico input) {
        return save(input, input.activo());
    }

    /**
     * @param activo null keeps the stored value on update (true on insert); otherwise the new value
     * @return the stored row
     */
    public Medico save(Medico input, Boolean activo) {
        access.require(Rol.ADMIN);
        Medico clean = normalize(input);
        if (clean.id() == null) {
            clean = withActivo(clean, activo == null || activo);
            validate(clean);
            long id = medicos.insert(clean);
            return withId(clean, id);
        }
        long id = clean.id();
        Medico stored = medicos.findById(id)
            .orElseThrow(() -> new NotFoundException("Medico " + id + " not found"));
        clean = withActivo(clean, activo == null ? stored.activo() : activo);
        validate(clean);
        medicos.update(clean);
        return clean;
    }

    public DeleteOutcome delete(long id) {
        access.require(Rol.ADMIN);
        medicos.findById(id).orElseThrow(() -> new NotFoundException("Medico " + id + " not found"));
        if (medicos.hasAssociatedHistory(id)) {
            medicos.setActivo(id, false);
            return DeleteOutcome.DEACTIVATED;
        }
        try {
            medicos.delete(id);
            return DeleteOutcome.DELETED;
        } catch (HasHistoryException ex) {
            // history appeared between the check and the delete: deactivate instead
            medicos.setActivo(id, false);
            return DeleteOutcome.DEACTIVATED;
        }
    }

    private static Medico normalize(Medico m) {
        Long id = m.id() == null || m.id() == 0 ? null : m.id();
        return new Medico(id, Validation.clean(m.documento()), Validation.clean(m.nombres()),
            Validation.clean(m.apellidos()), Validation.clean(m.especialidad()), Validation.clean(m.telefono()),
            Validation.clean(m.correo()), m.activo());
    }

    private void validate(Medico m) {
        Validation v = new Validation();
        v.required("documento", m.documento());
        v.required("nombres", m.nombres());
        v.required("apellidos", m.apellidos());
        v.required("especialidad", m.especialidad());
        v.maxLength("documento", m.documento(), MAX_DOCUMENTO);
        v.maxLength("nombres", m.nombres(), MAX_NOMBRES);
        v.maxLength("apellidos", m.apellidos(), MAX_APELLIDOS);
        v.maxLength("especialidad", m.especialidad(), MAX_ESPECIALIDAD);
        v.maxLength("telefono", m.telefono(), MAX_TELEFONO);
        v.maxLength("correo", m.correo(), MAX_CORREO);
        v.email("correo", m.correo());
        if (m.documento() != null && !v.errors().containsKey("documento")) {
            Optional<Medico> other = medicos.findByDocumento(m.documento());
            if (other.isPresent() && !other.get().id().equals(m.id())) {
                v.reject("documento", Validation.DUPLICATE_DOCUMENTO);
            }
        }
        v.throwIfInvalid();
    }

    private static Medico withActivo(Medico m, boolean activo) {
        return new Medico(m.id(), m.documento(), m.nombres(), m.apellidos(), m.especialidad(), m.telefono(),
            m.correo(), activo);
    }

    private static Medico withId(Medico m, long id) {
        return new Medico(id, m.documento(), m.nombres(), m.apellidos(), m.especialidad(), m.telefono(),
            m.correo(), m.activo());
    }
}
