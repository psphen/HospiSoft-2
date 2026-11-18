package com.hospisoft.bridge;

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
