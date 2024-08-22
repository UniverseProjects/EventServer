package com.universeprojects.eventserver;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;

import java.io.IOException;

public class AuthService {
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String CONFIG_REMOTE_HOST = "remote_host";
    public static final String CONFIG_REMOTE_PORT = "remote_port";
    public static final String CONFIG_REMOTE_SSL = "remote_ssl";
    public static final String CONFIG_AUTH_ENDPOINT = "remote_auth_endpoint";
    public static final String CONFIG_HEADER_NAME = "remote_api_header_name";
    public static final String CONFIG_HEADER_VALUE = "remote_api_header_value";
    public static final int MAX_ATTEMPTS = 5;

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final EventServerVerticle verticle;
    private final String authEndpoint;
    private final String headerName;
    private final String headerValue;
    private final WebClient client;

    public AuthService(EventServerVerticle verticle) {
        this.verticle = verticle;
        String host = Config.getString(CONFIG_REMOTE_HOST, "localhost");
        int port = Config.getInt(CONFIG_REMOTE_PORT, 8886);
        boolean ssl = Config.getBoolean(CONFIG_REMOTE_SSL, false);
        authEndpoint = Config.getString(CONFIG_AUTH_ENDPOINT, "/chatAuth");
        headerName = Config.getString(CONFIG_HEADER_NAME, "api-key");
        headerValue = Config.getString(CONFIG_HEADER_VALUE, null);

        WebClientOptions options = new WebClientOptions().
            setDefaultHost(host).
            setDefaultPort(port).
            setSsl(ssl).
            setFollowRedirects(false);

        client = WebClient.create(verticle.getVertx(), options);
    }

    public void authenticate(String token, Handler<AuthResponse> handler, Handler<Throwable> exceptionHandler) {
        runAuthRequest(token, handler, exceptionHandler, 1);
    }

    private void runAuthRequest(String token, Handler<AuthResponse> handler, Handler<Throwable> exceptionHandler, int attempt) {
        final HttpRequest<JsonObject> request = client
            .get(authEndpoint)
            .addQueryParam("token", token)
            .as(BodyCodec.jsonObject());
        if (headerName != null && headerValue != null) {
            request.putHeader(headerName, headerValue);
        }

        request.send((result) -> {
            if (result.succeeded()) {
                HttpResponse<JsonObject> response = result.result();
                if (response.statusCode() == HttpResponseStatus.OK.code()) {
                    final JsonObject json = response.body();
                    final AuthResponse authResponse = AuthResponse.fromJson(json);
                    handler.handle(authResponse);
                } else {
                    if (attempt < MAX_ATTEMPTS) {
                        runAuthRequest(token, handler, exceptionHandler, attempt + 1);
                        log.warn("Auth-attempt returned status code " + response.statusCode() +
                            " - retrying (attmpt " + attempt + ")");
                    } else {
                        exceptionHandler.handle(new IOException("Bad status code " +
                            response.statusCode() + " in auth response after " + attempt + " attempts - giving up"));
                    }
                }
            } else {
                exceptionHandler.handle(result.cause());
            }
        });
    }
}
