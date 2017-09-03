package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;

public class AuthService {
    public static final String CONFIG_REMOTE_HOST = "remote.host";
    public static final String CONFIG_REMOTE_PORT = "remote.port";
    public static final String CONFIG_REMOTE_SSL = "remote.ssl";
    public static final String CONFIG_AUTH_ENDPOINT = "remote.auth.endpoint";
    public static final String CONFIG_HEADER_NAME = "remote.api.header.name";
    public static final String CONFIG_HEADER_VALUE = "remote.api.header.value";

    private final String authEndpoint;
    private final String headerName;
    private final String headerValue;
    private final HttpClient client;

    public AuthService(EventServerVerticle verticle) {
        String host = Config.getString(CONFIG_REMOTE_HOST, "www.universeprojects.com");
        int port = Config.getInt(CONFIG_REMOTE_PORT, 443);
        boolean ssl = Config.getBoolean(CONFIG_REMOTE_SSL, true);
        authEndpoint = Config.getString(CONFIG_AUTH_ENDPOINT, "/chatAuth");
        headerName = Config.getString(CONFIG_HEADER_NAME, "api_key");
        headerValue = Config.getString(CONFIG_HEADER_VALUE, null);

        HttpClientOptions options = new HttpClientOptions().
                setDefaultHost(host).
                setDefaultPort(port).
                setSsl(ssl);
        client = verticle.getVertx().createHttpClient(options);
    }

    public void authenticate(String token, Handler<AuthResponse> handler, Handler<Throwable> exceptionHandler) {
        final HttpClientRequest request = client.get(authEndpoint + "?token=" + token);
        if(headerName != null && headerValue != null) {
            request.putHeader(headerName, headerValue);
        }
        request.handler((response) ->
                response.bodyHandler((buffer) -> {
                    final JsonObject json = buffer.toJsonObject();
                    final AuthResponse authResponse = AuthResponse.fromJson(json);
                    handler.handle(authResponse);
                })
        );
        request.exceptionHandler(exceptionHandler);
        request.end();
    }
}
