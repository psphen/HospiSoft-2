package com.hospisoft.controller;

import com.hospisoft.bridge.BadRequestException;
import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Dtos;
import com.hospisoft.bridge.Payload;
import com.hospisoft.dao.MedicamentoFilter;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.StockState;
import com.hospisoft.service.MedicamentoInput;
import com.hospisoft.service.MedicamentoService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MVC controller of the medicamento module: receives the View's request from the JS bridge,
 * parses the payload, delegates to {@link MedicamentoService} (the Model) and returns the DTO.
 *
 * <p>Same payload pattern as the medico/paciente modules: list
 * {@code {"filter":{"texto","activo","stockState"},"page","pageSize"}}, get/delete {@code {"id"}},
 * save = dto ({@code activo} optional; on update {@code stockExpected} = the stock loaded into the
 * form, else VALIDATION on stock; a changed stock answers CONFLICT). DTO adds the derived
 * {@code stockState}.
 */
public final class MedicamentoController implements Controller {

    private final MedicamentoService medicamentos;

    public MedicamentoController(MedicamentoService medicamentos) {
        this.medicamentos = medicamentos;
    }

    @Override
    public void register(Bridge bridge) {
        bridge.register("listMedicamentos", this::list);
        bridge.register("getMedicamento", this::get);
        bridge.register("saveMedicamento", this::save);
        bridge.register("deleteMedicamento", this::delete);
    }

    private Object list(String payload) {
        Map<String, Object> body = Payload.object(payload);
        Map<String, Object> f = Payload.optObject(body, "filter");
        MedicamentoFilter filter = new MedicamentoFilter(Payload.optString(f, "texto"),
            Payload.optBoolean(f, "activo"), stockState(Payload.optString(f, "stockState")));
        return Dtos.toMap(medicamentos.list(filter, Paging.page(body), Paging.pageSize(body)),
            MedicamentoController::toMap);
    }

    private Object get(String payload) {
        return toMap(medicamentos.get(Payload.requireLong(Payload.object(payload), "id")));
    }

    private Object save(String payload) {
        Map<String, Object> body = Payload.object(payload);
        MedicamentoInput input = new MedicamentoInput(
            Payload.optLong(body, "id"),
            Payload.optString(body, "codigo"),
            Payload.optString(body, "nombre"),
            Payload.optString(body, "descripcion"),
            Payload.optString(body, "presentacion"),
            body.get("stock"),
            body.get("stockMinimo"),
            Payload.optLong(body, "stockExpected"));
        return toMap(medicamentos.save(input, Payload.optBoolean(body, "activo")));
    }

    private Object delete(String payload) {
        return Map.of("outcome", medicamentos.delete(Payload.requireLong(Payload.object(payload), "id")));
    }

    private static StockState stockState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return StockState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown stockState: " + raw);
        }
    }

    private static Map<String, Object> toMap(Medicamento m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id());
        map.put("codigo", m.codigo());
        map.put("nombre", m.nombre());
        map.put("descripcion", m.descripcion());
        map.put("presentacion", m.presentacion());
        map.put("stock", m.stock());
        map.put("stockMinimo", m.stockMinimo());
        map.put("activo", m.activo());
        map.put("stockState", m.stockState());
        return map;
    }
}
