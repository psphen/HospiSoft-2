package com.hospisoft.service;

/**
 * Unchecked: the export file could not be generated or written (folder missing, no permission, file
 * open in another program). Reported as code EXPORT_FAILED with a Spanish message; the cause is
 * kept for the log only, since paths can carry the user's name.
 */
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
