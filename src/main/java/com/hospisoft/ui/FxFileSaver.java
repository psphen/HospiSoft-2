package com.hospisoft.ui;

import com.hospisoft.export.ExportFormat;
import com.hospisoft.export.FileSaver;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

public final class FxFileSaver implements FileSaver {

    private final Supplier<Window> owner;
    private File lastDirectory;

    public FxFileSaver(Supplier<Window> owner) {
        this.owner = owner;
    }

    @Override
    public Optional<Path> choose(String suggestedFileName, ExportFormat format) {
        if (Platform.isFxApplicationThread()) {
            return show(suggestedFileName, format);
        }
        CompletableFuture<Optional<Path>> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                answer.complete(show(suggestedFileName, format));
            } catch (Throwable ex) {
                answer.completeExceptionally(ex);
            }
        });
        try {
            return answer.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException ex) {
            throw new IllegalStateException("save dialog failed", ex.getCause());
        }
    }

    private Optional<Path> show(String suggestedFileName, ExportFormat format) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Guardar exportación (" + format.label() + ")");
        chooser.setInitialFileName(suggestedFileName);
        FileChooser.ExtensionFilter filter =
            new FileChooser.ExtensionFilter(format.description(), "*." + format.extension());
        chooser.getExtensionFilters().add(filter);
        chooser.setSelectedExtensionFilter(filter);
        File initial = initialDirectory(lastDirectory, System.getProperty("user.home"));
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        File chosen = chooser.showSaveDialog(owner.get());
        if (chosen == null) {
            return Optional.empty();
        }
        lastDirectory = chosen.getParentFile();
        return Optional.of(chosen.toPath());
    }

    static File initialDirectory(File last, String userHome) {
        if (last != null && last.isDirectory()) {
            return last;
        }
        if (userHome == null || userHome.isBlank()) {
            return null;
        }
        File home = new File(userHome);
        File documents = new File(home, "Documents");
        if (documents.isDirectory()) {
            return documents;
        }
        return home.isDirectory() ? home : null;
    }
}
