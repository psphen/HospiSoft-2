package com.hospisoft.service;

import com.hospisoft.dao.HasHistoryException;
import com.hospisoft.dao.MedicamentoFilter;
import com.hospisoft.dao.MedicamentoRepository;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Medicamento use cases. ADMIN has full CRUD; MEDICO and PACIENTE are read-only (stock lookup).
 * Deleting a medicamento referenced by a receta deactivates it instead.
 *
 * <p>Business rules beyond the schema: {@code presentacion} is required here although the column
 * is nullable. Updates are protected against lost stock changes: the client must send the stock it
 * loaded ({@code stockExpected}); if the row's stock differs the update is rejected with
 * {@link ConflictException} and the client reloads. Every successful write is recorded in the audit log.
 */
public final class MedicamentoService {

    static final int MAX_CODIGO = 20;
    static final int MAX_NOMBRE = 100;
    static final int MAX_DESCRIPCION = 200;
    static final int MAX_PRESENTACION = 80;

    static final String IN_RECETAS_SUFFIX = " (referenciado en recetas)";

    private final MedicamentoRepository medicamentos;
    private final Access access;
    private final AuditLog audit;

    public MedicamentoService(MedicamentoRepository medicamentos, Supplier<Optional<SessionContext>> session,
                              AuditLog audit) {
        this.medicamentos = medicamentos;
        this.access = new Access(session);
        this.audit = audit;
    }

    public Page<Medicamento> list(MedicamentoFilter filter, int page, int pageSize) {
        access.require(Rol.ADMIN, Rol.MEDICO, Rol.PACIENTE);
        MedicamentoFilter safe = filter == null ? MedicamentoFilter.NONE : filter;
        Validation.checkFilterText(safe.texto());
        return medicamentos.findPage(safe, page, pageSize);
    }

    /** Every row {@link #list} would page through (same permission), up to the export cap. */
    public ExportRows<Medicamento> listForExport(MedicamentoFilter filter) {
        return ExportRows.collect((page, pageSize) -> list(filter, page, pageSize));
    }

    public Medicamento get(long id) {
        access.require(Rol.ADMIN, Rol.MEDICO, Rol.PACIENTE);
        return medicamentos.findById(id).orElseThrow(() -> new NotFoundException("Medicamento " + id + " not found"));
    }

    /**
     * Insert when the id is null or 0, update otherwise (update requires {@code stockExpected}).
     *
     * @param activo null keeps the stored value on update (true on insert); otherwise the new value
     * @throws ConflictException when the stored stock differs from {@code stockExpected}
     */
    public Medicamento save(MedicamentoInput input, Boolean activo) {
        access.require(Rol.ADMIN);
        Long id = input.id() == null || input.id() == 0 ? null : input.id();
        boolean storedActivo = true;
        if (id != null) {
            long existingId = id;
            storedActivo = medicamentos.findById(existingId)
                .orElseThrow(() -> new NotFoundException("Medicamento " + existingId + " not found")).activo();
        }
        boolean effectiveActivo = activo != null ? activo : (id == null || storedActivo);
        Medicamento clean = validate(id, input, effectiveActivo);
        if (id == null) {
            long newId = medicamentos.insert(clean);
            Medicamento created = new Medicamento(newId, clean.codigo(), clean.nombre(), clean.descripcion(),
                clean.presentacion(), clean.stock(), clean.stockMinimo(), clean.activo());
            audit.record(AccionAuditoria.CREAR, ModuloAuditoria.MEDICAMENTO, newId, detalleConStock(created));
            return created;
        }
        medicamentos.update(clean, Math.toIntExact(input.stockExpected()));
        audit.record(AccionAuditoria.EDITAR, ModuloAuditoria.MEDICAMENTO, id, detalleConStock(clean));
        return clean;
    }

    public DeleteOutcome delete(long id) {
        access.require(Rol.ADMIN);
        Medicamento found = medicamentos.findById(id)
            .orElseThrow(() -> new NotFoundException("Medicamento " + id + " not found"));
        DeleteOutcome outcome = deleteOrDeactivate(id);
        if (outcome == DeleteOutcome.DELETED) {
            audit.record(AccionAuditoria.ELIMINAR, ModuloAuditoria.MEDICAMENTO, id, detalle(found));
        } else {
            audit.record(AccionAuditoria.DESACTIVAR, ModuloAuditoria.MEDICAMENTO, id, detalle(found) + IN_RECETAS_SUFFIX);
        }
        return outcome;
    }

    /** Audit text, e.g. "Medicamento MED-001 - Acetaminofen 500 mg". */
    private static String detalle(Medicamento m) {
        return "Medicamento " + m.codigo() + " - " + m.nombre();
    }

    private static String detalleConStock(Medicamento m) {
        return detalle(m) + " (stock " + m.stock() + ")";
    }

    private DeleteOutcome deleteOrDeactivate(long id) {
        if (medicamentos.hasAssociatedHistory(id)) {
            medicamentos.setActivo(id, false);
            return DeleteOutcome.DEACTIVATED;
        }
        try {
            medicamentos.delete(id);
            return DeleteOutcome.DELETED;
        } catch (HasHistoryException ex) {
            medicamentos.setActivo(id, false);
            return DeleteOutcome.DEACTIVATED;
        }
    }

    private Medicamento validate(Long id, MedicamentoInput in, boolean activo) {
        Validation v = new Validation();
        String codigo = Validation.clean(in.codigo());
        String nombre = Validation.clean(in.nombre());
        String descripcion = Validation.clean(in.descripcion());
        String presentacion = Validation.clean(in.presentacion());
        v.required("codigo", codigo);
        v.required("nombre", nombre);
        v.required("presentacion", presentacion);
        v.maxLength("codigo", codigo, MAX_CODIGO);
        v.maxLength("nombre", nombre, MAX_NOMBRE);
        v.maxLength("descripcion", descripcion, MAX_DESCRIPCION);
        v.maxLength("presentacion", presentacion, MAX_PRESENTACION);
        int stock = v.nonNegativeInt("stock", in.stock());
        int stockMinimo = v.nonNegativeInt("stockMinimo", in.stockMinimo());
        if (id != null && (in.stockExpected() == null || in.stockExpected() < 0
            || in.stockExpected() > Integer.MAX_VALUE)) {
            v.reject("stock", Validation.RELOAD_RECORD);
        }
        if (codigo != null && !v.errors().containsKey("codigo")) {
            Optional<Medicamento> other = medicamentos.findByCodigo(codigo);
            if (other.isPresent() && !other.get().id().equals(id)) {
                v.reject("codigo", Validation.DUPLICATE_CODIGO);
            }
        }
        v.throwIfInvalid();
        return new Medicamento(id, codigo, nombre, descripcion, presentacion, stock, stockMinimo, activo);
    }
}
