package com.hospisoft.bridge;

/**
 * The object actually exposed to JavaScript as {@code window.app}.
 *
 * <p>LiveConnect exposes every public method of the injected object, so this facade exists to keep
 * the surface minimal: only the eight methods below are reachable from the page. Lifecycle and
 * registration methods of {@link Bridge} ({@code register}, {@code shutdown},
 * {@code asyncMethodNames}) are deliberately not delegated. {@code JsApiTest} pins this set.
 */
public final class JsApi {

    private final Bridge bridge;

    public JsApi(Bridge bridge) {
        this.bridge = bridge;
    }

    public String call(String requestId, String method, String payloadJson) {
        return bridge.call(requestId, method, payloadJson);
    }

    public String login(String jsonCredentials) {
        return bridge.login(jsonCredentials);
    }

    public String logout() {
        return bridge.logout();
    }

    public String getSession() {
        return bridge.getSession();
    }

    public String getAllowedPages() {
        return bridge.getAllowedPages();
    }

    public String getStartupStatus() {
        return bridge.getStartupStatus();
    }

    public String navigate(String page) {
        return bridge.navigate(page);
    }

    public void log(String message) {
        bridge.log(message);
    }
}
