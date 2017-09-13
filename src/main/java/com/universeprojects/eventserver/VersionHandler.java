package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VersionHandler implements Handler<RoutingContext> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public void handle(RoutingContext context) {
        final InputStream stream = getClass().getResourceAsStream("build.properties");
        Buffer buffer = Buffer.buffer();
        if(stream != null) {
            Properties properties = new Properties();
            try {
                properties.load(stream);
                JsonObject json = new JsonObject();
                for(String key : properties.stringPropertyNames()) {
                    String value = properties.getProperty(key);
                    json.put(key, value);
                }
                buffer.appendString(json.encodePrettily());
            } catch (IOException ex) {
                log.warn("Error loading build.properties", ex);
            }
        }
        context.response()
            .putHeader("Content-Type", "application/json")
            .end(buffer);
    }
}
