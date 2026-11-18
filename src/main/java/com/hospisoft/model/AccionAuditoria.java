package com.hospisoft.model;

public enum AccionAuditoria {
    CREAR("Crear"),
    EDITAR("Editar"),
    ELIMINAR("Eliminar"),
    DESACTIVAR("Desactivar"),
    REPROGRAMAR("Reprogramar"),
    CANCELAR("Cancelar"),
    INICIAR_ATENCION("Iniciar atención"),
    FINALIZAR_ATENCION("Finalizar atención"),
    LOGIN("Inicio de sesión"),
    LOGIN_FALLIDO("Inicio de sesión fallido"),
    LOGOUT("Cierre de sesión"),
    EXPORTAR("Exportar");

    private final String label;

    AccionAuditoria(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
