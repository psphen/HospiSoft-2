package com.hospisoft.controller;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Dtos;
import com.hospisoft.bridge.Payload;
import com.hospisoft.dao.MedicoFilter;
import com.hospisoft.model.Medico;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.ExportSpec;
import com.hospisoft.service.MedicoService;
import java.util.List;
import java.util.Map;

public final class MedicoController implements Controller {

    static final ExportSpec EXPORT = new ExportSpec(ModuloAuditoria.MEDICO, "medicos", "Listado de Médicos",
        List.of("#", "Documento", "Nombre completo", "Especialidad", "Teléfono", "Email", "Activo"),
        List.of(1, 3, 6, 4, 3, 5, 1));

    private final MedicoService medicos;
    private final ExportService exports;

    public MedicoController(MedicoService medicos, ExportService exports) {
        this.medicos = medicos;
        this.exports = exports;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("listMedicos", this::list);
        bridge.register("getMedico", this::get);
        bridge.register("saveMedico", this::save);
        bridge.register("deleteMedico", this::delete);
        bridge.register("listEspecialidades", this::especialidades);
        bridge.register("exportMedicos", this::export);
    }

    private static MedicoFilter filter(Map<String, Object> body) {
        Map<String, Object> f = Payload.optObject(body, "filter");
        return new MedicoFilter(Payload.optString(f, "especialidad"), Payload.optBoolean(f, "activo"),
            Payload.optString(f, "texto"));
    }

    private Object list(String payload) {
        Map<String, Object> body = Payload.object(payload);
        return Dtos.toMap(medicos.list(filter(body), Paging.page(body), Paging.pageSize(body)), Dtos::toMap);
    }

    private Object get(String payload) {
        return Dtos.toMap(medicos.get(Payload.requireLong(Payload.object(payload), "id")));
    }

    private Object save(String payload) {
        Map<String, Object> body = Payload.object(payload);
        Medico input = Dtos.medicoFrom(body);
        return Dtos.toMap(medicos.save(input, Payload.optBoolean(body, "activo")));
    }

    private Object delete(String payload) {
        return Map.of("outcome", medicos.delete(Payload.requireLong(Payload.object(payload), "id")));
    }

    private Object especialidades(String payload) {
        return medicos.especialidades();
    }

    private Object export(String payload) {
        Map<String, Object> body = Payload.object(payload);
        MedicoFilter filter = filter(body);
        var format = Exports.format(body);
        return Exports.toMap(exports.export(EXPORT, format, medicos.listForExport(filter).map(MedicoController::row)));
    }

    private static List<String> row(Medico m) {
        return List.of(Exports.text(m.id()), Exports.text(m.documento()), m.nombreCompleto(),
            Exports.text(m.especialidad()), Exports.text(m.telefono()), Exports.text(m.correo()),
            Exports.yesNo(m.activo()));
    }
}
