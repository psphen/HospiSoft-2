package com.hospisoft.controller;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Dtos;
import com.hospisoft.bridge.Payload;
import com.hospisoft.dao.MedicoFilter;
import com.hospisoft.model.Medico;
import com.hospisoft.service.MedicoService;
import java.util.Map;

/**
 * MVC controller of the medico module: receives the View's request from the JS bridge, parses
 * the payload, delegates to {@link MedicoService} (the Model) and returns the DTO.
 *
 * <p>Payloads:
 * <ul>
 *   <li>listMedicos: {@code {"filter":{"especialidad","activo","texto"},"page":1,"pageSize":10}} ->
 *       {@code {"items":[dto],"total":N,"page":P,"pageSize":S,"totalPages":T}}</li>
 *   <li>getMedico / deleteMedico: {@code {"id":5}} -> dto / {@code {"outcome":"DELETED|DEACTIVATED"}}</li>
 *   <li>saveMedico: the dto (insert when id is missing or 0; {@code activo} optional: true on insert,
 *       unchanged on update when omitted) -> the stored dto</li>
 *   <li>listEspecialidades: ignored -> {@code ["..."]}</li>
 * </ul>
 */
public final class MedicoController implements Controller {

    private final MedicoService medicos;

    public MedicoController(MedicoService medicos) {
        this.medicos = medicos;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("listMedicos", this::list);
        bridge.register("getMedico", this::get);
        bridge.register("saveMedico", this::save);
        bridge.register("deleteMedico", this::delete);
        bridge.register("listEspecialidades", this::especialidades);
    }

    private Object list(String payload) {
        Map<String, Object> body = Payload.object(payload);
        Map<String, Object> f = Payload.optObject(body, "filter");
        MedicoFilter filter = new MedicoFilter(Payload.optString(f, "especialidad"),
            Payload.optBoolean(f, "activo"), Payload.optString(f, "texto"));
        return Dtos.toMap(medicos.list(filter, Paging.page(body), Paging.pageSize(body)), Dtos::toMap);
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

    /** The payload is ignored. */
    private Object especialidades(String payload) {
        return medicos.especialidades();
    }
}
