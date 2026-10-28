package com.hospisoft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    @Test
    void reportsNotFoundWhenNoCandidateExists(@TempDir Path dir) {
        AppConfig config = AppConfig.load(List.of(dir.resolve("config.properties")));
        assertEquals(AppConfig.State.NOT_FOUND, config.state());
        assertEquals(dir.resolve("config.properties"), config.expectedPath());
    }

    @Test
    void reportsMissingKeys(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, "db.url=jdbc:postgresql://h:5432/postgres\n", StandardCharsets.UTF_8);
        AppConfig config = AppConfig.load(List.of(file));
        assertEquals(AppConfig.State.KEYS_MISSING, config.state());
        assertEquals(List.of("db.user", "db.password"), config.missingKeys());
    }

    @Test
    void blankValuesCountAsMissing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, "db.url=x\ndb.user=   \ndb.password=p\n", StandardCharsets.UTF_8);
        assertEquals(List.of("db.user"), AppConfig.load(List.of(file)).missingKeys());
    }

    @Test
    void loadsValuesAndDefaultPoolSize(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, "db.url=jdbc:postgresql://h:5432/postgres?sslmode=require\ndb.user=u\ndb.password=p\n",
            StandardCharsets.UTF_8);
        AppConfig config = AppConfig.load(List.of(file));
        assertEquals(AppConfig.State.OK, config.state());
        assertEquals("jdbc:postgresql://h:5432/postgres?sslmode=require", config.url());
        assertEquals("u", config.user());
        assertEquals("p", config.password());
        assertEquals(3, config.poolSize());
        assertTrue(config.missingKeys().isEmpty());
    }

    @Test
    void passwordsAreReadAsUtf8AndVerbatim(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.properties");
        // In .properties a backslash must be doubled; trailing spaces are part of the value.
        Files.writeString(file, "db.url=x\ndb.user=u\ndb.password=contrase\u00f1a\u20ac\\\\x  \n",
            StandardCharsets.UTF_8);
        AppConfig config = AppConfig.load(List.of(file));
        assertEquals(AppConfig.State.OK, config.state());
        assertEquals("contrase\u00f1a\u20ac\\x  ", config.password());
    }

    @Test
    void unreadableFileIsAReadError(@TempDir Path dir) {
        // A directory passes no isRegularFile check here: fromFile is exercised directly.
        AppConfig config = AppConfig.fromFile(dir);
        assertEquals(AppConfig.State.READ_ERROR, config.state());
        assertEquals(dir, config.expectedPath());
    }

    @Test
    void malformedUnicodeEscapeIsAReadError(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, "db.url=x\ndb.user=u\ndb.password=\\uZZZZ\n", StandardCharsets.UTF_8);
        assertEquals(AppConfig.State.READ_ERROR, AppConfig.load(List.of(file)).state());
    }

    @Test
    void firstExistingCandidateWins(@TempDir Path dir) throws IOException {
        Path first = dir.resolve("a.properties");
        Path second = dir.resolve("b.properties");
        Files.writeString(second, "db.url=x\ndb.user=u\ndb.password=p\ndb.pool.size=5\n", StandardCharsets.UTF_8);
        AppConfig config = AppConfig.load(List.of(first, second));
        assertEquals(AppConfig.State.OK, config.state());
        assertEquals(5, config.poolSize());
        assertEquals(second, config.sourcePath());
    }

    @Test
    void theJpackageAppImageRootIsResolvedFromTheJarDirectory(@TempDir Path dir) throws IOException {
        // jpackage app-image layout: HospiSoft/HospiSoft.exe and HospiSoft/app/hospisoft-desktop.jar
        Path image = Files.createDirectories(dir.resolve("HospiSoft"));
        Path appDir = Files.createDirectories(image.resolve("app"));
        Files.writeString(appDir.resolve("HospiSoft.cfg"), "[Application]\n", StandardCharsets.UTF_8);
        assertEquals(image, AppConfig.appImageRootOf(appDir));
        assertNull(AppConfig.appImageRootOf(dir.resolve("target")), "only an 'app' directory has a root");

        // a config.properties next to the launcher is found even though the jar is one level deeper
        Path besideLauncher = image.resolve("config.properties");
        Files.writeString(besideLauncher, "db.url=x\ndb.user=u\ndb.password=p\n", StandardCharsets.UTF_8);
        AppConfig config = AppConfig.load(List.of(appDir.resolve("config.properties"), besideLauncher));
        assertEquals(AppConfig.State.OK, config.state());
        assertEquals(besideLauncher, config.sourcePath());
    }

    @Test
    void anAppDirectoryWithoutALauncherConfigIsNotAnImage(@TempDir Path dir) throws IOException {
        // a plain Maven/Gradle module called "app" must not make the parent directory a candidate
        Path appDir = Files.createDirectories(dir.resolve("workspace").resolve("app"));
        Files.writeString(appDir.resolve("hospisoft-desktop.jar"), "not really a jar", StandardCharsets.UTF_8);
        assertNull(AppConfig.appImageRootOf(appDir), "no .cfg means no jpackage image");
        assertNull(AppConfig.appImageRootOf(dir.resolve("app")), "a missing directory is not an image");
    }

    @Test
    void defaultCandidatesStartWithTheSystemPropertyAndEndWithTheWorkingDirectory(@TempDir Path dir)
        throws IOException {
        Path explicit = dir.resolve("custom").resolve("config.properties");
        String previous = System.getProperty(AppConfig.SYSTEM_PROPERTY);
        System.setProperty(AppConfig.SYSTEM_PROPERTY, explicit.toString());
        try {
            List<Path> candidates = AppConfig.defaultCandidates();
            assertEquals(explicit.toAbsolutePath().normalize(), candidates.get(0),
                "an explicit -Dhospisoft.config always wins");
            Path workingDir = Path.of("").toAbsolutePath().resolve(AppConfig.FILE_NAME);
            assertEquals(workingDir, candidates.get(candidates.size() - 1),
                "the working directory is the last resort");
            // running from target/test-classes there is no jar, so only the two ends are present
            assertTrue(candidates.size() >= 2 && candidates.size() <= 4, "unexpected candidates: " + candidates);
            assertEquals(candidates.size(), candidates.stream().distinct().count(), "candidates are de-duplicated");
        } finally {
            if (previous == null) {
                System.clearProperty(AppConfig.SYSTEM_PROPERTY);
            } else {
                System.setProperty(AppConfig.SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    void invalidPoolSizeFallsBackToDefault(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, "db.url=x\ndb.user=u\ndb.password=p\ndb.pool.size=abc\n", StandardCharsets.UTF_8);
        assertEquals(3, AppConfig.load(List.of(file)).poolSize());
    }
}
