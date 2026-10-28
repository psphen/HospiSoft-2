package com.hospisoft.bridge;

import com.hospisoft.dao.MedicamentoFilter;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.StockState;
import com.hospisoft.service.MedicamentoInput;
import com.hospisoft.service.MedicamentoService;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers the medicamento methods on the bridge (phase 3). Same payload pattern as phase 2:
 * list {@code {"filter":{"texto","activo","stockState"},"page","pageSize"}}, get/delete {@code {"id"}},
 * save = dto ({@code activo} optional; on update {@code stockExpected} = the stock loaded into the
 * form, else VALIDATION on stock; a changed stock answers CONFLICT). DTO adds the derived
 * {@code stockState}.
 */
public final class MedicamentoMethods {

    private MedicamentoMethods() {
    }

    public static void register(Bridge bridge, MedicamentoService medicamentos) {
        bridge.register("listMedicamentos", payload -> {
            Map<String, Object> body = Payload.object(payload);
            Map<String, Object> f = Payload.optObject(body, "filter");
            MedicamentoFilter filter = new MedicamentoFilter(Payload.optString(f, "texto"),
                Payload.optBoolean(f, "activo"), stockState(Payload.optString(f, "stockState")));
            return Dtos.toMap(medicamentos.list(filter, CrudMethods.page(body), CrudMethods.pageSize(body)),
                MedicamentoMethods::toMap);
        });
        bridge.register("getMedicamento", payload ->
            toMap(medicamentos.get(Payload.requireLong(Payload.object(payload), "id"))));
        bridge.register("saveMedicamento", payload -> {
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
        });
        bridge.register("deleteMedicamento", payload ->
            Map.of("outcome", medicamentos.delete(Payload.requireLong(Payload.object(payload), "id"))));
    }

    static StockState stockState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return StockState.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown stockState: " + raw);
        }
    }

    public static Map<String, Object> toMap(Medicamento m) {
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
