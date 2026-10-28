package com.hospisoft.service;

import com.hospisoft.model.Rol;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable description of the logged-in user.
 *
 * @param idReferencia id of the linked medico or paciente (null for ADMIN)
 */
public record SessionContext(long userId, String username, Rol rol, Long idReferencia, String displayName) {

    /** JSON-friendly view consumed by the web layer. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", userId);
        map.put("username", username);
        map.put("rol", rol);
        map.put("idReferencia", idReferencia);
        map.put("displayName", displayName);
        return map;
    }
}
