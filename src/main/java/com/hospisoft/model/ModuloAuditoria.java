package com.hospisoft.model;

public enum ModuloAuditoria {
    PACIENTE("Pacientes"),
    MEDICO("Médicos"),
    MEDICAMENTO("Medicamentos"),
    CITA("Citas"),
    ATENCION("Atención"),
    SESION("Sesión"),
    AUDITORIA("Auditoría");

    private final String label;

    ModuloAuditoria(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
