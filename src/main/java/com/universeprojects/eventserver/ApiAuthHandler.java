package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;

public class ApiAuthHandler implements Handler<RoutingContext> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String CONFIG_HEADER_NAME = "api_header_name";
    public static final String CONFIG_HEADER_VALUE = "api_header_value";

    private final String headerName;
    private final String headerValue;
    private final CharSequence headerNameChar;

    public ApiAuthHandler() {
        this.headerName = Config.getString(CONFIG_HEADER_NAME, "api-key");
        this.headerValue = Config.getString(CONFIG_HEADER_VALUE, null);
        this.headerNameChar = headerName != null ? HttpHeaders.createOptimized(headerName) : null;

    }

    @Override
    public void handle(RoutingContext context) {
        if(headerName != null && headerValue != null) {
            String value = context.request().getHeader(headerNameChar);
            if(!headerValue.equals(value)) {
                log.warn("Attempt to call /send with bad header-value " + value + " for name " + headerName + " from " + context.request().remoteAddress().host());
                context.response().setStatusCode(403);
                context.response().end();
                return;
            }
        }
        context.next();
    }
}
