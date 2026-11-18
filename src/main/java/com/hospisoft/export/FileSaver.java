package com.hospisoft.export;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Port to the "save as" dialog. The JavaFX implementation lives in the UI layer; tests use a fake.
 * Called from the bridge thread: implementations must marshal onto their UI thread themselves.
 */
public interface FileSaver {

    /**
     * @param suggestedFileName initial file name, extension included (e.g. pacientes_2026-11-04.xlsx)
     * @return the chosen path, or empty when the user cancelled
     */
    Optional<Path> choose(String suggestedFileName, ExportFormat format);
}
