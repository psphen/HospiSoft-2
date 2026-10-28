package com.hospisoft.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * External database configuration (config.properties). Never bundled, never committed.
 *
 * <p>Search order: the system property {@code hospisoft.config}; for a jpackage app-image, the
 * directory of the launcher ({@code <image>}, where the user naturally drops the file); the
 * directory of the running jar; the current working directory. The file is read as UTF-8 so
 * passwords with non-ASCII characters survive. Construction never throws: {@link #state()} reports
 * what was found so the UI can explain the problem.
 */
public final class AppConfig {

    public static final String FILE_NAME = "config.properties";
    public static final String SYSTEM_PROPERTY = "hospisoft.config";
    public static final int DEFAULT_POOL_SIZE = 3;

    private static final List<String> REQUIRED_KEYS = List.of("db.url", "db.user", "db.password");

    public enum State {
        OK,
        NOT_FOUND,
        READ_ERROR,
        KEYS_MISSING
    }

    private final State state;
    private final Path sourcePath;
    private final Path expectedPath;
    private final List<String> missingKeys;
    private final String url;
    private final String user;
    private final String password;
    private final int poolSize;

    private AppConfig(State state, Path sourcePath, Path expectedPath, List<String> missingKeys,
                      String url, String user, String password, int poolSize) {
        this.state = state;
        this.sourcePath = sourcePath;
        this.expectedPath = expectedPath;
        this.missingKeys = List.copyOf(missingKeys);
        this.url = url;
        this.user = user;
        this.password = password;
        this.poolSize = poolSize;
    }

    /** Loads using the default search order. */
    public static AppConfig load() {
        return load(defaultCandidates());
    }

    /** Loads from the first existing candidate path. */
    public static AppConfig load(List<Path> candidates) {
        Path expected = candidates.isEmpty() ? Paths.get(FILE_NAME).toAbsolutePath() : candidates.get(0);
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return fromFile(candidate);
            }
        }
        return new AppConfig(State.NOT_FOUND, null, expected, List.of(), null, null, null, DEFAULT_POOL_SIZE);
    }

    /** Package-private for tests. */
    static AppConfig fromFile(Path file) {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException ex) {
            // IllegalArgumentException: malformed \\u escape inside the file
            return new AppConfig(State.READ_ERROR, file, file, List.of(), null, null, null, DEFAULT_POOL_SIZE);
        }
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            String value = props.getProperty(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        int poolSize = parsePoolSize(props.getProperty("db.pool.size"));
        State state = missing.isEmpty() ? State.OK : State.KEYS_MISSING;
        return new AppConfig(state, file, file, missing,
            trim(props.getProperty("db.url")), trim(props.getProperty("db.user")),
            props.getProperty("db.password"), poolSize);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static int parsePoolSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_POOL_SIZE;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 1 ? value : DEFAULT_POOL_SIZE;
        } catch (NumberFormatException ex) {
            return DEFAULT_POOL_SIZE;
        }
    }

    /** System property, jar directory, working directory (in that order, de-duplicated). */
    public static List<Path> defaultCandidates() {
        List<Path> candidates = new ArrayList<>();
        String explicit = System.getProperty(SYSTEM_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Paths.get(explicit).toAbsolutePath().normalize());
        }
        Path jarDir = runningJarDirectory();
        if (jarDir != null) {
            // jpackage app-image: the jar lives in <image>/app while the launcher sits in <image>,
            // so the launcher's directory is the natural place for the file and is reported first.
            Path appImageRoot = appImageRootOf(jarDir);
            if (appImageRoot != null) {
                addUnique(candidates, appImageRoot.resolve(FILE_NAME));
            }
            addUnique(candidates, jarDir.resolve(FILE_NAME));
        }
        addUnique(candidates, Paths.get("").toAbsolutePath().resolve(FILE_NAME));
        return candidates;
    }

    /**
     * The directory holding the launcher of a jpackage app-image, given the directory of the jar
     * ({@code <image>/app}); null for any other layout. A folder merely named "app" is not enough:
     * jpackage always writes its launcher configuration as {@code app/<Name>.cfg}, so that file is
     * the evidence this really is an image.
     */
    static Path appImageRootOf(Path jarDir) {
        Path name = jarDir.getFileName();
        if (name == null || !"app".equals(name.toString()) || jarDir.getParent() == null) {
            return null;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(jarDir)) {
            boolean looksLikeAppImage = entries.anyMatch(entry -> Files.isRegularFile(entry)
                && entry.getFileName().toString().endsWith(".cfg"));
            return looksLikeAppImage ? jarDir.getParent() : null;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static void addUnique(List<Path> list, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!list.contains(normalized)) {
            list.add(normalized);
        }
    }

    /** Directory of the running jar, or null when running from a classes directory (dev). */
    static Path runningJarDirectory() {
        try {
            CodeSource source = AppConfig.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            URL location = source.getLocation();
            if (location == null) {
                return null;
            }
            Path path = Paths.get(location.toURI());
            if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                return path.getParent();
            }
            return null;
        } catch (URISyntaxException | RuntimeException ex) {
            return null;
        }
    }

    public State state() {
        return state;
    }

    /** File actually loaded (or that failed to load), null when not found. */
    public Path sourcePath() {
        return sourcePath;
    }

    /** Where the file is expected (first candidate), useful for error messages. */
    public Path expectedPath() {
        return expectedPath;
    }

    public List<String> missingKeys() {
        return missingKeys;
    }

    public String url() {
        return url;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public int poolSize() {
        return poolSize;
    }
}
