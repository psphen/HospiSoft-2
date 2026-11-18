package com.hospisoft.service;

import com.hospisoft.dao.HasHistoryException;
import com.hospisoft.dao.MedicoFilter;
import com.hospisoft.dao.MedicoRepository;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.Medico;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class MedicoService {

    static final int MAX_DOCUMENTO = 20;
    static final int MAX_NOMBRES = 80;
    static final int MAX_APELLIDOS = 80;
    static final int MAX_ESPECIALIDAD = 80;
    static final int MAX_TELEFONO = 20;
    static final int MAX_CORREO = 120;

    static final String HAS_HISTORY_SUFFIX = " (tiene historial asociado)";

    private final MedicoRepository medicos;
    private final Access access;
    private final AuditLog audit;

    public MedicoService(MedicoRepository medicos, Supplier<Optional<SessionContext>> session, AuditLog audit) {
        this.medicos = medicos;
        this.access = new Access(session);
        this.audit = audit;
    }

    public Page<Medico> list(MedicoFilter filter, int page, int pageSize) {
        access.require(Rol.ADMIN);
        MedicoFilter safe = filter == null ? MedicoFilter.NONE : filter;
        Validation.checkFilterText(safe.texto());
        return medicos.findPage(safe, page, pageSize);
    }

    public ExportRows<Medico> listForExport(MedicoFilter filter) {
        return ExportRows.collect((page, pageSize) -> list(filter, page, pageSize));
    }

    public Medico get(long id) {
        access.require(Rol.ADMIN);
        return medicos.findById(id).orElseThrow(() -> new NotFoundException("Medico " + id + " not found"));
    }

    public List<String> especialidades() {
        access.require(Rol.ADMIN);
        return medicos.findEspecialidades();
    }

    public Medico save(Medico input) {
        return save(input, input.activo());
    }

    public Medico save(Medico input, Boolean activo) {
        access.require(Rol.ADMIN);
        Medico clean = normalize(input);
        if (clean.id() == null) {
            clean = withActivo(clean, activo == null || activo);
            validate(clean);
            long id = medicos.insert(clean);
            Medico created = withId(clean, id);
            audit.record(AccionAuditoria.CREAR, ModuloAuditoria.MEDICO, id, detalle(created));
            return created;
        }
        long id = clean.id();
        Medico stored = medicos.findById(id)
            .orElseThrow(() -> new NotFoundException("Medico " + id + " not found"));
        clean = withActivo(clean, activo == null ? stored.activo() : activo);
        validate(clean);
        medicos.update(clean);
        audit.record(AccionAuditoria.EDITAR, ModuloAuditoria.MEDICO, id, detalle(clean));
        return clean;
    }

    public DeleteOutcome delete(long id) {
        access.require(Rol.ADMIN);
        Medico found = medicos.findById(id).orElseThrow(() -> new NotFoundException("Medico " + id + " not found"));
        DeleteOutcome outcome = deleteOrDeactivate(id);
        if (outcome == DeleteOutcome.DELETED) {
            audit.record(AccionAuditoria.ELIMINAR, ModuloAuditoria.MEDICO, id, detalle(found));
        } else {
            audit.record(AccionAuditoria.DESACTIVAR, ModuloAuditoria.MEDICO, id, detalle(found) + HAS_HISTORY_SUFFIX);
        }
        return outcome;
    }

    private static String detalle(Medico m) {
        return "Médico " + m.documento() + " - " + m.nombres() + " " + m.apellidos() + " (" + m.especialidad() + ")";
    }

    private DeleteOutcome deleteOrDeactivate(long id) {
        if (medicos.hasAssociatedHistory(id)) {
            medicos.setActivo(id, false);
            return DeleteOutcome.DEACTIVATED;
        }
        try {
            medicos.delete(id);
            return DeleteOutcome.DELETED;
        } catch (HasHistoryException ex) {
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
