package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.Map;

public class UpdateUsersHandler implements Handler<RoutingContext> {

    private final EventServerVerticle verticle;

    public UpdateUsersHandler(EventServerVerticle verticle) {
        this.verticle = verticle;
    }

    @Override
    public void handle(RoutingContext context) {
        if(context.request().method() != HttpMethod.POST) {
            context.response().setStatusCode(405);
            context.response().end();
            return;
        }

        context.request().bodyHandler((buffer) -> {
            JsonObject json = buffer.toJsonObject();
            JsonObject userChannels = json.getJsonObject("userChannels");
            for(Map.Entry<String, Object> entry : userChannels.getMap().entrySet()) {
                String userId = entry.getKey();
                JsonArray channels = (JsonArray) entry.getValue();
                String address = verticle.generateUserUpdateAddress(userId);
                verticle.eventBus.publish(address, channels);
            }
            context.response().end();
        });
    }
}
