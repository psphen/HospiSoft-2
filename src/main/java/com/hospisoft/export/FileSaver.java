package com.hospisoft.export;

import java.nio.file.Path;
import java.util.Optional;

public interface FileSaver {
    Optional<Path> choose(String suggestedFileName, ExportFormat format);
}
