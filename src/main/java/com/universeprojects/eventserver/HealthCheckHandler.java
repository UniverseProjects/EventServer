package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

public class HealthCheckHandler implements Handler<RoutingContext> {

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final EventServerVerticle verticle;

    public HealthCheckHandler(EventServerVerticle verticle) {
        this.verticle = verticle;
    }

    @Override
    public void handle(RoutingContext context) {
        context.response().end(Buffer.buffer("ok"));
    }
}
