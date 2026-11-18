package com.hospisoft.controller;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Dtos;
import com.hospisoft.bridge.Payload;
import com.hospisoft.dao.PacienteFilter;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Paciente;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.ExportSpec;
import com.hospisoft.service.PacienteService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class PacienteController implements Controller {

    static final ExportSpec EXPORT = new ExportSpec(ModuloAuditoria.PACIENTE, "pacientes", "Listado de Pacientes",
        List.of("#", "Documento", "Nombre completo", "Edad", "Género", "Teléfono", "Email", "Activo"),
        List.of(1, 3, 6, 1, 2, 3, 5, 1));
    private static final Map<String, String> SEXO_LABEL = Map.of("M", "Masculino", "F", "Femenino", "OTRO", "Otro");

    private final PacienteService pacientes;
    private final ExportService exports;

    public PacienteController(PacienteService pacientes, ExportService exports) {
        this.pacientes = pacientes;
        this.exports = exports;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("listPacientes", this::list);
        bridge.register("getPaciente", this::get);
        bridge.register("savePaciente", this::save);
        bridge.register("deletePaciente", this::delete);
        bridge.register("exportPacientes", this::export);
    }

    private static PacienteFilter filter(Map<String, Object> body) {
        Map<String, Object> f = Payload.optObject(body, "filter");
        return new PacienteFilter(Payload.optString(f, "sexo"), Payload.optBoolean(f, "activo"),
            Payload.optString(f, "texto"));
    }

    private Object list(String payload) {
        Map<String, Object> body = Payload.object(payload);
        return Dtos.toMap(pacientes.list(filter(body), Paging.page(body), Paging.pageSize(body)),
            p -> Dtos.toMap(p, pacientes.today()));
    }

    private Object get(String payload) {
        return Dtos.toMap(pacientes.get(Payload.requireLong(Payload.object(payload), "id")), pacientes.today());
    }

    private Object save(String payload) {
        Map<String, Object> body = Payload.object(payload);
        Paciente input = Dtos.pacienteFrom(body);
        return Dtos.toMap(pacientes.save(input, Payload.optBoolean(body, "activo")), pacientes.today());
    }

    private Object delete(String payload) {
        return Map.of("outcome", pacientes.delete(Payload.requireLong(Payload.object(payload), "id")));
    }

    private Object export(String payload) {
        Map<String, Object> body = Payload.object(payload);
        PacienteFilter filter = filter(body);
        var format = Exports.format(body);
        LocalDate today = pacientes.today();
        return Exports.toMap(exports.export(EXPORT, format,
            pacientes.listForExport(filter).map(p -> row(p, today))));
    }

    private static List<String> row(Paciente p, LocalDate today) {
        Integer edad = p.edad(today);
        return List.of(Exports.text(p.id()), Exports.text(p.documento()), p.nombreCompleto(),
            edad == null ? "" : String.valueOf(edad), SEXO_LABEL.getOrDefault(p.sexo(), Exports.text(p.sexo())),
            Exports.text(p.telefono()), Exports.text(p.correo()), Exports.yesNo(p.activo()));
    }
}
