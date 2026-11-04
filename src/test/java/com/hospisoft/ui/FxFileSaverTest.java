package com.hospisoft.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The dialog itself needs a running JavaFX toolkit; these are its pure folder rules. */
class FxFileSaverTest {

    @TempDir
    Path home;

    @Test
    void startsInDocumentsWhenTheFolderExists() throws IOException {
        Path documents = Files.createDirectory(home.resolve("Documents"));
        assertEquals(documents.toFile(), FxFileSaver.initialDirectory(null, home.toString()));
    }

    @Test
    void fallsBackToTheHomeFolder() {
        assertEquals(home.toFile(), FxFileSaver.initialDirectory(null, home.toString()));
    }

    @Test
    void prefersTheFolderUsedLastWhileItStillExists() throws IOException {
        Path last = Files.createDirectory(home.resolve("exports"));
        assertEquals(last.toFile(), FxFileSaver.initialDirectory(last.toFile(), home.toString()));
        Files.delete(last);
        assertEquals(home.toFile(), FxFileSaver.initialDirectory(last.toFile(), home.toString()));
    }

    @Test
    void noUsableFolderLetsTheDialogDecide() {
        assertNull(FxFileSaver.initialDirectory(null, null));
        assertNull(FxFileSaver.initialDirectory(null, home.resolve("missing").toString()));
    }
}
