package com.hospisoft.controller;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Dtos;
import com.hospisoft.bridge.Payload;
import com.hospisoft.dao.PacienteFilter;
import com.hospisoft.model.Paciente;
import com.hospisoft.service.PacienteService;
import java.util.Map;

/**
 * MVC controller of the paciente module: receives the View's request from the JS bridge, parses
 * the payload, delegates to {@link PacienteService} (the Model) and returns the DTO (which carries
 * the age computed against the service's {@code today()}).
 *
 * <p>Payloads:
 * <ul>
 *   <li>listPacientes: {@code {"filter":{"sexo","activo","texto"},"page":1,"pageSize":10}} ->
 *       {@code {"items":[dto],"total":N,"page":P,"pageSize":S,"totalPages":T}}</li>
 *   <li>getPaciente / deletePaciente: {@code {"id":5}} -> dto / {@code {"outcome":"DELETED|DEACTIVATED"}}</li>
 *   <li>savePaciente: the dto (insert when id is missing or 0; {@code activo} optional: true on insert,
 *       unchanged on update when omitted) -> the stored dto</li>
 * </ul>
 */
public final class PacienteController implements Controller {

    private final PacienteService pacientes;

    public PacienteController(PacienteService pacientes) {
        this.pacientes = pacientes;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("listPacientes", this::list);
        bridge.register("getPaciente", this::get);
        bridge.register("savePaciente", this::save);
        bridge.register("deletePaciente", this::delete);
    }

    private Object list(String payload) {
        Map<String, Object> body = Payload.object(payload);
        Map<String, Object> f = Payload.optObject(body, "filter");
        PacienteFilter filter = new PacienteFilter(Payload.optString(f, "sexo"),
            Payload.optBoolean(f, "activo"), Payload.optString(f, "texto"));
        return Dtos.toMap(pacientes.list(filter, Paging.page(body), Paging.pageSize(body)),
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
}
