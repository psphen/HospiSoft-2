package com.hospisoft.bridge;

import com.hospisoft.dao.MedicoFilter;
import com.hospisoft.dao.PacienteFilter;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Paciente;
import com.hospisoft.service.MedicoService;
import com.hospisoft.service.PacienteService;
import java.util.Map;

/**
 * Registers the medico and paciente methods on the bridge (phase 2).
 *
 * <p>Payloads:
 * <ul>
 *   <li>listX: {@code {"filter":{...},"page":1,"pageSize":10}} ->
 *       {@code {"items":[dto],"total":N,"page":P,"pageSize":S,"totalPages":T}}</li>
 *   <li>getX / deleteX: {@code {"id":5}} -> dto / {@code {"outcome":"DELETED|DEACTIVATED"}}</li>
 *   <li>saveX: the dto (insert when id is missing or 0; {@code activo} optional: true on insert,
 *       unchanged on update when omitted) -> the stored dto</li>
 *   <li>listEspecialidades: ignored -> {@code ["..."]}</li>
 * </ul>
 */
public final class CrudMethods {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE = 1_000_000;

    private CrudMethods() {
    }

    public static void register(Bridge bridge, MedicoService medicos, PacienteService pacientes) {
        bridge.register("listMedicos", payload -> {
            Map<String, Object> body = Payload.object(payload);
            Map<String, Object> f = Payload.optObject(body, "filter");
            MedicoFilter filter = new MedicoFilter(Payload.optString(f, "especialidad"),
                Payload.optBoolean(f, "activo"), Payload.optString(f, "texto"));
            return Dtos.toMap(medicos.list(filter, page(body), pageSize(body)), Dtos::toMap);
        });
        bridge.register("getMedico", payload ->
            Dtos.toMap(medicos.get(Payload.requireLong(Payload.object(payload), "id"))));
        bridge.register("saveMedico", payload -> {
            Map<String, Object> body = Payload.object(payload);
            Medico input = Dtos.medicoFrom(body);
            return Dtos.toMap(medicos.save(input, Payload.optBoolean(body, "activo")));
        });
        bridge.register("deleteMedico", payload ->
            Map.of("outcome", medicos.delete(Payload.requireLong(Payload.object(payload), "id"))));
        bridge.register("listEspecialidades", payload -> medicos.especialidades());

        bridge.register("listPacientes", payload -> {
            Map<String, Object> body = Payload.object(payload);
            Map<String, Object> f = Payload.optObject(body, "filter");
            PacienteFilter filter = new PacienteFilter(Payload.optString(f, "sexo"),
                Payload.optBoolean(f, "activo"), Payload.optString(f, "texto"));
            return Dtos.toMap(pacientes.list(filter, page(body), pageSize(body)),
                p -> Dtos.toMap(p, pacientes.today()));
        });
        bridge.register("getPaciente", payload ->
            Dtos.toMap(pacientes.get(Payload.requireLong(Payload.object(payload), "id")), pacientes.today()));
        bridge.register("savePaciente", payload -> {
            Map<String, Object> body = Payload.object(payload);
            Paciente input = Dtos.pacienteFrom(body);
            return Dtos.toMap(pacientes.save(input, Payload.optBoolean(body, "activo")), pacientes.today());
        });
        bridge.register("deletePaciente", payload ->
            Map.of("outcome", pacientes.delete(Payload.requireLong(Payload.object(payload), "id"))));
    }

    static int page(Map<String, Object> body) {
        int page = Payload.optInt(body, "page", 1);
        if (page < 1 || page > MAX_PAGE) {
            throw new BadRequestException("page must be between 1 and " + MAX_PAGE);
        }
        return page;
    }

    static int pageSize(Map<String, Object> body) {
        int size = Payload.optInt(body, "pageSize", DEFAULT_PAGE_SIZE);
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }
}
