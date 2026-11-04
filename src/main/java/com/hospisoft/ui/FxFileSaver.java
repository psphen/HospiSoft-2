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

/**
 * JavaFX "save as" dialog owned by the main window (so it is modal and centred on it).
 *
 * <p>Export methods run on the bridge's background thread, but a FileChooser may only be shown on
 * the JavaFX application thread: {@link #choose} hands the dialog to {@code Platform.runLater} and
 * blocks the bridge thread on a {@link CompletableFuture} until the user answers. The FX thread
 * never waits for the bridge, so this cannot deadlock; the page keeps its promise pending (with
 * the long export timeout) while the dialog is open.
 */
public final class FxFileSaver implements FileSaver {

    private final Supplier<Window> owner;
    /** Folder of the last saved file, reused for the next export; touched on the FX thread only. */
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
            Thread.currentThread().interrupt(); // the bridge is shutting down: treat as cancelled
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

    /**
     * Folder the dialog opens in: the last one used while it still exists, else ~/Documents, else
     * the home folder; null (the platform default) when none of them exists.
     */
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
