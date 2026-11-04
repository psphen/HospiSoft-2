package com.hospisoft.model;

/**
 * Module an audit entry belongs to. Stored as text in auditoria.modulo (the CHECK constraint in
 * db/schema.sql lists exactly these names); {@link #label()} is the Spanish text shown in the UI.
 */
public enum ModuloAuditoria {
    PACIENTE("Pacientes"),
    MEDICO("Médicos"),
    MEDICAMENTO("Medicamentos"),
    CITA("Citas"),
    ATENCION("Atención"),
    SESION("Sesión");

    private final String label;

    ModuloAuditoria(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
