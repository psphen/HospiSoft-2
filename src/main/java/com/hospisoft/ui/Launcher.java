package com.hospisoft.ui;

/**
 * Entry point of the fat jar.
 *
 * <p>Deliberately does NOT extend {@code javafx.application.Application}: when the main class
 * extends Application, the launcher checks for the JavaFX runtime modules and a shaded jar fails
 * with "JavaFX runtime components are missing". Delegating to {@link MainApp} avoids that check.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
