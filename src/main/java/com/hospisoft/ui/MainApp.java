package com.hospisoft.ui;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Json;
import com.hospisoft.config.AppConfig;
import com.hospisoft.bridge.AtencionMethods;
import com.hospisoft.bridge.CitaMethods;
import com.hospisoft.bridge.CrudMethods;
import com.hospisoft.bridge.DashboardMethods;
import com.hospisoft.bridge.JsApi;
import com.hospisoft.bridge.MedicamentoMethods;
import com.hospisoft.dao.AtencionDao;
import com.hospisoft.dao.AtencionRepository;
import com.hospisoft.dao.CitaDao;
import com.hospisoft.dao.DashboardDao;
import com.hospisoft.dao.DashboardRepository;
import com.hospisoft.dao.CitaRepository;
import com.hospisoft.dao.MedicamentoDao;
import com.hospisoft.dao.MedicamentoRepository;
import com.hospisoft.dao.MedicoDao;
import com.hospisoft.dao.MedicoRepository;
import com.hospisoft.dao.PacienteDao;
import com.hospisoft.dao.PacienteRepository;
import com.hospisoft.dao.UnavailableRepository;
import com.hospisoft.dao.UsuarioDao;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.db.ConnectionPool;
import com.hospisoft.db.SchemaChecker;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.AtencionService;
import com.hospisoft.service.CitaService;
import com.hospisoft.service.DashboardService;
import com.hospisoft.service.CachedStartupProbe;
import com.hospisoft.service.DefaultStartupProbe;
import com.hospisoft.service.MedicamentoService;
import com.hospisoft.service.MedicoService;
import com.hospisoft.service.PacienteService;
import com.hospisoft.service.SessionHolder;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

/** Composition root: wires config, database, services, the JS bridge and the WebView. */
public class MainApp extends Application {

    private static final String TITLE = "HospiSoft";
    private static final double WIDTH = 1280;
    private static final double HEIGHT = 800;
    private static final double MIN_WIDTH = 1024;
    private static final double MIN_HEIGHT = 700;
    private static final String LOAD_ERROR_MESSAGE = "No se pudo cargar la página";

    /** Forwards console output, uncaught errors and unhandled rejections to Bridge.log. */
    private static final String CONSOLE_SHIM = """
        (function () {
          var app = window.app;
          function fmt(args) {
            return Array.prototype.map.call(args, function (a) {
              if (a instanceof Error) { return a.message + (a.stack ? '\\n' + a.stack : ''); }
              if (typeof a === 'object' && a !== null) { try { return JSON.stringify(a); } catch (e) { return String(a); } }
              return String(a);
            }).join(' ');
          }
          function fwd(level) { return function () { try { app.log('[' + level + '] ' + fmt(arguments)); } catch (e) {} }; }
          window.console.log = fwd('log');
          window.console.info = fwd('info');
          window.console.warn = fwd('warn');
          window.console.error = fwd('error');
          window.console.debug = fwd('debug');
          window.onerror = function (msg, src, line, col) {
            try { app.log('[uncaught] ' + msg + ' (' + src + ':' + line + ':' + col + ')'); } catch (e) {}
            return false;
          };
          window.addEventListener('unhandledrejection', function (event) {
            var reason = event && event.reason;
            try { app.log('[unhandledrejection] ' + fmt([reason])); } catch (e) {}
          });
        })();
        """;

    // Strong references: the JS-side "app" object is a weak wrapper around this instance.
    private Bridge bridge;
    private JsApi jsApi;
    private ConnectionPool pool;
    private CachedStartupProbe startupProbe;
    private SessionHolder sessions;
    private WebEngine engine;
    private PageRouter router;
    private String pendingLoadError;

    @Override
    public void start(Stage stage) {
        AppConfig config = AppConfig.load();
        sessions = new SessionHolder();

        UsuarioRepository usuarios;
        MedicoRepository medicos;
        PacienteRepository pacientes;
        MedicamentoRepository medicamentos;
        CitaRepository citas;
        AtencionRepository atenciones;
        DashboardRepository dashboard;
        SchemaChecker schemaChecker = null;
        if (config.state() == AppConfig.State.OK) {
            pool = ConnectionPool.forJdbc(config.url(), config.user(), config.password(), config.poolSize());
            usuarios = new UsuarioDao(pool);
            medicos = new MedicoDao(pool);
            pacientes = new PacienteDao(pool);
            medicamentos = new MedicamentoDao(pool);
            citas = new CitaDao(pool);
            atenciones = new AtencionDao(pool);
            dashboard = new DashboardDao(pool);
            schemaChecker = new SchemaChecker(pool);
            System.out.println("[config] loaded " + config.sourcePath());
        } else {
            System.out.println("[config] " + config.state() + " (expected at " + config.expectedPath() + ")");
            usuarios = UnavailableRepository.create(UsuarioRepository.class);
            medicos = UnavailableRepository.create(MedicoRepository.class);
            pacientes = UnavailableRepository.create(PacienteRepository.class);
            medicamentos = UnavailableRepository.create(MedicamentoRepository.class);
            citas = UnavailableRepository.create(CitaRepository.class);
            atenciones = UnavailableRepository.create(AtencionRepository.class);
            dashboard = UnavailableRepository.create(DashboardRepository.class);
        }
        startupProbe = new CachedStartupProbe(new DefaultStartupProbe(config, schemaChecker));
        startupProbe.setListener(status -> Platform.runLater(this::notifyStartupStatus));

        WebView view = new WebView();
        engine = view.getEngine();
        engine.setJavaScriptEnabled(true);
        router = new PageRouter(engine, sessions);
        bridge = new Bridge(new AuthService(usuarios), sessions, startupProbe, router, this::runScriptLater);
        CrudMethods.register(bridge,
            new MedicoService(medicos, sessions::current),
            new PacienteService(pacientes, sessions::current));
        MedicamentoMethods.register(bridge, new MedicamentoService(medicamentos, sessions::current));
        java.time.Clock clock = java.time.Clock.systemDefaultZone();
        AppZone appZone = AppZone.systemDefault();
        CitaMethods.register(bridge, new CitaService(citas, medicos, pacientes, sessions::current, clock, appZone));
        AtencionMethods.register(bridge, new AtencionService(atenciones, citas, medicamentos, pacientes,
            sessions::current, clock, appZone));
        DashboardMethods.register(bridge, new DashboardService(dashboard, medicamentos, sessions::current,
            clock, appZone));
        jsApi = new JsApi(bridge); // only the facade is reachable from JavaScript

        engine.setOnAlert(event -> System.out.println("[web alert] " + Bridge.sanitizeLog(event.getData())));
        engine.setOnError(event -> System.err.println("[web error] " + event.getMessage()));
        engine.locationProperty().addListener((obs, oldLocation, newLocation) -> router.enforceGuard(newLocation));
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> onLoadState(newState));

        Scene scene = new Scene(view, WIDTH, HEIGHT);
        stage.setTitle(TITLE);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setScene(scene);
        stage.show();

        router.load(AuthGuard.HOME_PAGE); // guard sends the user to login.html when there is no session
    }

    private void onLoadState(Worker.State state) {
        if (state == Worker.State.SUCCEEDED) {
            if (router.enforceGuard(engine.getLocation())) {
                return;
            }
            injectBridge();
        } else if (state == Worker.State.FAILED) {
            onLoadFailed();
        }
    }

    /** Re-injected on every page load: the document (and its window object) is new each time. */
    private void injectBridge() {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("app", jsApi);
        } catch (RuntimeException ex) {
            System.err.println("[web] bridge injection failed: " + ex);
            return;
        }
        try {
            engine.executeScript(CONSOLE_SHIM);
        } catch (RuntimeException ex) {
            System.err.println("[web] console shim failed: " + ex);
        } finally {
            runScript("window.dispatchEvent(new Event('app-ready'));");
        }
        if (pendingLoadError != null && AuthGuard.LOGIN_PAGE.equals(AuthGuard.pageNameOf(engine.getLocation()))) {
            String message = pendingLoadError;
            pendingLoadError = null;
            runScript("window.dispatchEvent(new CustomEvent('load-error', { detail: " + Json.write(message) + " }));");
        }
    }

    /** A page failed to load: fall back to the login page with an inline message. */
    private void onLoadFailed() {
        String location = engine.getLocation();
        Throwable cause = engine.getLoadWorker().getException();
        System.err.println("[web] load failed: " + AuthGuard.pageNameOf(location) + " " + cause);
        if (AuthGuard.LOGIN_PAGE.equals(AuthGuard.pageNameOf(location))) {
            return; // the fallback itself failed: nothing further to try
        }
        pendingLoadError = LOAD_ERROR_MESSAGE;
        sessions.clear(); // a broken page must not keep a half-initialized session alive
        router.load(AuthGuard.LOGIN_PAGE);
    }

    private void notifyStartupStatus() {
        runScript("window.dispatchEvent(new Event('startup-status'));");
    }

    /** Bridge.ScriptSink: marshals a script onto the JavaFX thread. */
    private void runScriptLater(String script) {
        Platform.runLater(() -> runScript(script));
    }

    private void runScript(String script) {
        try {
            engine.executeScript(script);
        } catch (RuntimeException ex) {
            System.err.println("[web] script failed: " + ex);
        }
    }

    @Override
    public void stop() {
        if (bridge != null) {
            bridge.shutdown();
        }
        if (pool != null) {
            pool.close();
        }
    }

    public static void main(String[] args) {
        launch(MainApp.class, args);
    }
}
