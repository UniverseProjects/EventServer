package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;

import java.util.logging.Logger;

public class ApiAuthHandler implements Handler<RoutingContext> {

    private final Logger log = Logger.getLogger(getClass().getCanonicalName());

    public static final String CONFIG_HEADER_NAME = "api.header.name";
    public static final String CONFIG_HEADER_VALUE = "api.header.value";

    private final String headerName;
    private final String headerValue;
    private final CharSequence headerNameChar;

    public ApiAuthHandler() {
        this.headerName = Config.getString(CONFIG_HEADER_NAME, null);
        this.headerValue = Config.getString(CONFIG_HEADER_VALUE, null);
        this.headerNameChar = headerName != null ? HttpHeaders.createOptimized(headerName) : null;

    }

    @Override
    public void handle(RoutingContext context) {
        if(headerName != null && headerValue != null) {
            String value = context.request().getHeader(headerNameChar);
            if(!headerValue.equals(value)) {
                log.warning("Attempt to call /send with bad header-value " + value + " for name " + headerName + " from " + context.request().remoteAddress().host());
                context.response().setStatusCode(403);
                context.response().end();
                return;
            }
        }
        context.next();
    }
}
