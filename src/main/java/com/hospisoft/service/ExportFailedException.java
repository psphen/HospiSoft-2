package com.hospisoft.service;

public class ExportFailedException extends RuntimeException {

    public static final String WRITE_FAILED = "No se pudo guardar el archivo. Verifique que la carpeta exista, "
        + "que tenga permisos de escritura y que el archivo no esté abierto en otro programa.";
    public static final String RENDER_FAILED = "No se pudo generar el archivo de exportación.";

    private final String userMessage;

    public ExportFailedException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
