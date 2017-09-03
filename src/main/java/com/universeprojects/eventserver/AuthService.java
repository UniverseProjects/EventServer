package com.universeprojects.eventserver;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.IOException;

public class AuthService {
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String CONFIG_REMOTE_HOST = "remote.host";
    public static final String CONFIG_REMOTE_PORT = "remote.port";
    public static final String CONFIG_REMOTE_SSL = "remote.ssl";
    public static final String CONFIG_AUTH_ENDPOINT = "remote.auth.endpoint";
    public static final String CONFIG_HEADER_NAME = "remote.api.header.name";
    public static final String CONFIG_HEADER_VALUE = "remote.api.header.value";
    public static final int MAX_ATTEMPTS = 5;

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final EventServerVerticle verticle;
    private final String authEndpoint;
    private final String headerName;
    private final String headerValue;
    private final HttpClient client;

    public AuthService(EventServerVerticle verticle) {
        this.verticle = verticle;
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
        runAuthRequest(token, handler, exceptionHandler, 1);
    }

    private void runAuthRequest(String token, Handler<AuthResponse> handler, Handler<Throwable> exceptionHandler, int attempt) {
        final HttpClientRequest request = client.get(authEndpoint + "?token=" + token);
        if (headerName != null && headerValue != null) {
            request.putHeader(headerName, headerValue);
        }
        request.handler((response) -> {
            if (response.statusCode() == HttpResponseStatus.OK.code()) {
                response.bodyHandler((buffer) -> {
                    final AuthResponse authResponse;
                    try {
                        final JsonObject json = buffer.toJsonObject();
                        authResponse = AuthResponse.fromJson(json);
                    } catch (Exception ex) {
                        exceptionHandler.handle(ex);
                        return;
                    }
                    handler.handle(authResponse);
                });
            } else {
                if (attempt < MAX_ATTEMPTS) {
                    runAuthRequest(token, handler, exceptionHandler, attempt + 1);
                    log.warn("Auth-attempt returned status code "+response.statusCode()+" - retrying (attmpt "+attempt+")");
                } else {
                    exceptionHandler.handle(new IOException("Bad status code "+response.statusCode()+" in auth response after " + attempt + " attempts - giving up"));
                }
            }
        });
        request.exceptionHandler(exceptionHandler);
        request.end();
    }
}
