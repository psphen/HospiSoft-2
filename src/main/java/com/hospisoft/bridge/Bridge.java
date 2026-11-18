package com.hospisoft.bridge;

import com.hospisoft.db.DatabaseAuthException;
import com.hospisoft.db.DatabaseUnavailableException;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AuditLog;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.AuthenticationException;
import com.hospisoft.service.ConflictException;
import com.hospisoft.service.ExportFailedException;
import com.hospisoft.service.NotFoundException;
import com.hospisoft.service.PageAccess;
import com.hospisoft.service.PermissionException;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupProbe;
import com.hospisoft.service.ValidationException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class Bridge {
    public interface Navigator {
        void navigate(String page);

        default boolean exists(String page) {
            return true;
        }
    }

    @FunctionalInterface
    public interface ScriptSink {
        void run(String script);
    }

    public static final String DB_UNAVAILABLE_MESSAGE = "Sin conexión con la base de datos";
    public static final String DB_AUTH_MESSAGE =
        "Credenciales de base de datos inválidas, revise config.properties";
    public static final String FORBIDDEN_MESSAGE = "Operación no permitida";
    public static final String NOT_FOUND_MESSAGE = "El registro no existe";
    public static final String INTERNAL_ERROR_MESSAGE = "Error interno de la aplicación";
    public static final int MAX_LOG_CHARS = 2000;
    public static final String LOGOUT_DETALLE = "Salida del sistema";

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AuthService authService;
    private final SessionHolder sessions;
    private final StartupProbe startupProbe;
    private final Navigator navigator;
    private final ScriptSink scriptSink;
    private final ExecutorService executor;
    private final AuditLog audit;
    private final Map<String, Function<String, String>> asyncMethods = new ConcurrentHashMap<>();

    public Bridge(AuthService authService, SessionHolder sessions, StartupProbe startupProbe,
                  Navigator navigator, ScriptSink scriptSink) {
        this(authService, sessions, startupProbe, navigator, scriptSink, AuditLog.NONE);
    }

    public Bridge(AuthService authService, SessionHolder sessions, StartupProbe startupProbe,
                  Navigator navigator, ScriptSink scriptSink, AuditLog audit) {
        this.authService = authService;
        this.sessions = sessions;
        this.startupProbe = startupProbe;
        this.navigator = navigator;
        this.scriptSink = scriptSink;
        this.audit = audit;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hospisoft-bridge");
            thread.setDaemon(true);
            return thread;
        });
        asyncMethods.put("login", this::loginEnvelope);
        asyncMethods.put("getStartupStatus", payload -> call(() -> startupProbe.probeBlocking().toMap()));
    }

    public void register(String method, Function<String, Object> body) {
        if (method == null || method.isBlank() || body == null) {
            throw new IllegalArgumentException("method and body are required");
        }
        asyncMethods.put(method, payload -> call(() -> body.apply(payload)));
    }

    public Set<String> asyncMethodNames() {
        return Set.copyOf(asyncMethods.keySet());
    }

    public String call(String requestId, String method, String payloadJson) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return error("BAD_REQUEST", "Invalid request id");
        }
        try {
            executor.execute(() -> {
                String envelope;
                try {
                    Function<String, String> handler = method == null ? null : asyncMethods.get(method);
                    envelope = handler == null
                        ? error("BAD_REQUEST", "Unknown method: " + method)
                        : handler.apply(payloadJson);
                } catch (Throwable ex) {
                    envelope = translate(ex);
                }
                try {
                    scriptSink.run(resolveScript(requestId, envelope));
                } catch (RuntimeException ex) {
                    System.err.println("[bridge] cannot deliver response " + requestId + ": " + ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            return error("INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE);
        }
        return ok("queued");
    }

    static String resolveScript(String requestId, String envelopeJson) {
        return "if (window.__hospisoft) { window.__hospisoft.resolve("
            + Json.write(requestId) + ", " + Json.write(envelopeJson) + "); }";
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public String login(String jsonCredentials) {
        return loginEnvelope(jsonCredentials);
    }

    public String logout() {
        return call(() -> {
            sessions.current().ifPresent(this::auditLogout);
            sessions.clear();
            return Boolean.TRUE;
        });
    }

    private void auditLogout(SessionContext ending) {
        try {
            executor.execute(() -> {
                try {
                    audit.recordAs(ending, AccionAuditoria.LOGOUT, ModuloAuditoria.SESION, null, LOGOUT_DETALLE);
                } catch (RuntimeException ex) {
                    System.err.println("[bridge] logout audit failed: " + describe(ex));
                }
            });
        } catch (RejectedExecutionException ex) {
            System.err.println("[bridge] logout audit skipped: bridge is shut down");
        }
    }

    public String getSession() {
        return call(() -> sessions.current().map(SessionContext::toMap).orElse(null));
    }

    public String getStartupStatus() {
        return call(() -> startupProbe.probe().toMap());
    }

    public String getAllowedPages() {
        return call(() -> PageAccess.allowedPages(currentRol()));
    }

    public String navigate(String page) {
        return call(() -> {
            if (!PageAccess.isKnownPage(page)) {
                throw new BadRequestException("Unknown page: " + page);
            }
            if (!PageAccess.isAllowed(page, currentRol())) {
                throw new PermissionException("Page not allowed for the current role: " + page);
            }
            if (!navigator.exists(page)) {
                throw new IllegalStateException("Missing page resource: " + page);
            }
            navigator.navigate(page);
            return page;
        });
    }

    public void log(String message) {
        try {
            System.out.println("[web] " + sanitizeLog(message));
        } catch (RuntimeException ignored) {}
    }

    public static String sanitizeLog(String message) {
        if (message == null) {
            return "null";
        }
        boolean truncated = message.length() > MAX_LOG_CHARS;
        String capped = truncated ? message.substring(0, MAX_LOG_CHARS) : message;
        StringBuilder sb = new StringBuilder(capped.length() + 16);
        for (int i = 0; i < capped.length(); i++) {
            char c = capped.charAt(i);
            boolean control = (c < 0x20 && c != '\t') || c == 0x7f || (c >= 0x80 && c <= 0x9f)
                || c == ' ' || c == ' ';
            if (!control) {
                sb.append(c);
            }
        }
        if (truncated) {
            sb.append(" [truncated]");
        }
        return sb.toString();
    }

    private String loginEnvelope(String jsonCredentials) {
        try {
            return ok(loginBody(jsonCredentials));
        } catch (DatabaseUnavailableException ex) {
            startupProbe.refresh();
            return translate(ex);
        } catch (Throwable ex) {
            return translate(ex);
        }
    }

    private Object loginBody(String jsonCredentials) {
        Map<String, Object> body = Payload.object(jsonCredentials);
        String username = Payload.requireString(body, "username");
        String password = Payload.requireString(body, "password");
        char[] chars = password.toCharArray();
        try {
            SessionContext session = authService.login(username, chars);
            sessions.set(session);
            return session.toMap();
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private Rol currentRol() {
        return sessions.current().map(SessionContext::rol).orElse(null);
    }

    private static String call(Supplier<Object> body) {
        try {
            return ok(body.get());
        } catch (Throwable ex) {
            return translate(ex);
        }
    }

    private static String translate(Throwable ex) {
        if (ex instanceof ValidationException validation) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("ok", false);
            envelope.put("code", "VALIDATION");
            envelope.put("message", ValidationException.MESSAGE);
            envelope.put("errors", validation.errors());
            return Json.write(envelope);
        }
        if (ex instanceof BadRequestException) {
            return error("BAD_REQUEST", safeMessage(ex, "Solicitud inválida"));
        }
        if (ex instanceof NotFoundException) {
            return error("NOT_FOUND", NOT_FOUND_MESSAGE);
        }
        if (ex instanceof ConflictException conflict) {
            return error("CONFLICT", conflict.userMessage());
        }
        if (ex instanceof ExportFailedException export) {
            return error("EXPORT_FAILED", export.userMessage());
        }
        if (ex instanceof AuthenticationException) {
            return error("AUTH_FAILED", ex.getMessage());
        }
        if (ex instanceof PermissionException) {
            return error("FORBIDDEN", FORBIDDEN_MESSAGE);
        }
        if (ex instanceof DatabaseUnavailableException) {
            return error("DB_UNAVAILABLE", DB_UNAVAILABLE_MESSAGE);
        }
        if (ex instanceof DatabaseAuthException) {
            return error("DB_AUTH_FAILED", DB_AUTH_MESSAGE);
        }
        System.err.println("[bridge] unexpected error: " + describe(ex));
        return error("INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE);
    }

    static String describe(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 5) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(current.getClass().getName());
            if (current instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                sb.append("[SQLState ").append(sql.getSQLState()).append(']');
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private static String ok(Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("data", data);
        return Json.write(envelope);
    }

    private static String error(String code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", false);
        envelope.put("code", code);
        envelope.put("message", message);
        return Json.write(envelope);
    }

    private static String safeMessage(Throwable ex, String fallback) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
