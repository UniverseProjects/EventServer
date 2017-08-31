package com.universeprojects.eventserver;


import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.sstore.LocalSessionStore;

public class EventServerVerticle extends AbstractVerticle {
    public static final String CONFIG_MODE = "server.mode";
    public static final String CONFIG_CORS_ORIGINS = "corsOrigins";

    private enum ServerMode {
        PROD, TEST
    }
    public EventBus eventBus;
    public SockJSHandler sockJSHandler;
    public AuthService authService;
    public SockJSSocketHandler sockJSSocketHandler;
    @SuppressWarnings("FieldCanBeLocal")
    public SharedDataService sharedDataService;
    public ServerMode serverMode;

    @Override
    public void start() {
        String corsOrigins = Config.getString(CONFIG_CORS_ORIGINS, "*");
        serverMode = Config.getEnum(CONFIG_MODE, ServerMode.class, ServerMode.PROD);
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        router.route().handler(CookieHandler.create());
        router.route().handler(CorsHandler.create(corsOrigins));
        router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)).setNagHttps(false));


        if(serverMode == ServerMode.TEST) {
            Route indexRoute = router.route("/");
            indexRoute.handler(routingContext ->
                    routingContext.response().sendFile("index.html")
            );
        }

        eventBus = vertx.eventBus();
        sharedDataService = new SharedDataService(vertx.sharedData());
        sockJSHandler = SockJSHandler.create(vertx);
        eventBus.registerDefaultCodec(ChatMessage.class, ChatMessageCodec.INSTANCE);

        SockJSHandlerOptions sockJSHandlerOptions = new SockJSHandlerOptions();

        authService = new AuthService(this);
        sockJSSocketHandler = new SockJSSocketHandler(this);

        Route socketRoute = router.route("/socket/*");
        socketRoute.handler(SockJSHandler.create(vertx, sockJSHandlerOptions).socketHandler(sockJSSocketHandler));

        server.requestHandler(router::accept).listen(6969, "0.0.0.0");
    }
}
