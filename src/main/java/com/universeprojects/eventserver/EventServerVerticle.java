package com.universeprojects.eventserver;


import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.sstore.LocalSessionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class EventServerVerticle extends AbstractVerticle {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String CONFIG_MODE = "server.mode";
    public static final String CONFIG_PORT = "server.port";
    public static final String CONFIG_CORS_ORIGINS = "cors.origins";
    public static final String CONFIG_LOG_CONNECTIONS = "log.connections";
    public static final String CONFIG_CHANNEL_HISTORY_SIZE = "channel.history.size";

    public enum ServerMode {
        PROD, TEST, TEST_CLIENT
    }
    public EventBus eventBus;
    public SockJSHandler sockJSHandler;
    public AuthService authService;
    public SockJSSocketHandler sockJSSocketHandler;
    @SuppressWarnings("FieldCanBeLocal")
    public SharedDataService sharedDataService;
    public ServerMode serverMode;
    public SlackCommunicationService slackCommunicationService;
    private boolean logConnections = false;
    private int channelHistorySize = 200;

    @Override
    public void start() {
        logConnections = Config.getBoolean(CONFIG_LOG_CONNECTIONS, false);
        String corsOrigins = Config.getString(CONFIG_CORS_ORIGINS, "*");
        int port = Config.getInt(CONFIG_PORT, 6969);
        serverMode = Config.getEnum(CONFIG_MODE, ServerMode.class, ServerMode.PROD);
        channelHistorySize = Config.getInt(CONFIG_CHANNEL_HISTORY_SIZE, 200);
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        router.route("/healthcheck").handler(new HealthCheckHandler(this));
        router.route("/version").blockingHandler(new VersionHandler());

        router.route().handler(CookieHandler.create());
        router.route().handler(CorsHandler.create(corsOrigins));
        router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)).setNagHttps(false));


        if(serverMode == ServerMode.TEST || serverMode == ServerMode.TEST_CLIENT) {
            Route indexRoute = router.route("/");
            indexRoute.handler(routingContext ->
                    routingContext.response().sendFile("index.html")
            );
        }

        eventBus = vertx.eventBus();
        sharedDataService = new SharedDataService(vertx.sharedData());
        eventBus.registerDefaultCodec(ChatMessage.class, ChatMessageCodec.INSTANCE);
        eventBus.registerDefaultCodec(ChatEnvelope.class, ChatEnvelopeCodec.INSTANCE);

        authService = new AuthService(this);
        sockJSSocketHandler = new SockJSSocketHandler(this);

        SockJSHandlerOptions sockJSHandlerOptions = new SockJSHandlerOptions();
        sockJSHandler = SockJSHandler.create(vertx, sockJSHandlerOptions).socketHandler(sockJSSocketHandler);

        Route socketRoute = router.route("/socket/*");
        socketRoute.handler(sockJSHandler);

        final ApiAuthHandler apiAuthHandler = new ApiAuthHandler();
        router.route("/send").handler(apiAuthHandler);
        router.route("/updateUsers").handler(apiAuthHandler);

        router.route("/send").handler(new IncomingMessageHandler(this));
        router.route("/updateUsers").handler(new UpdateUsersHandler(this));

        slackCommunicationService = new SlackCommunicationService(this);
        slackCommunicationService.activate();
        slackCommunicationService.setupRoute(router);

        server.requestHandler(router::accept).listen(port, "0.0.0.0");
        log.info("Server started up at http://localhost:"+port);
    }

    public String generateChannelAddress(String channel) {
        return "channel."+channel;
    }

    public String generateUserUpdateAddress(User user) {
        return generateUserUpdateAddress(user.userId);
    }

    public String generateUserUpdateAddress(String userId) {
        return "user.update."+userId;
    }

    public boolean shouldLogConnections() {
        return logConnections;
    }

    public void logConnectionEvent(Supplier<String> messageSupplier) {
        if(shouldLogConnections()) {
            log.info(messageSupplier.get());
        }
    }

    public void storeMessages(String channel, List<ChatMessage> messages) {
        sharedDataService.getMessageMap((mapResult) -> {
            if(mapResult.succeeded()) {
                final AsyncMap<String, JsonArray> map = mapResult.result();
                map.get(channel, (result) -> {
                    List<JsonObject> list;
                    if (result.succeeded() && result.result() != null) {
                        //noinspection unchecked
                        list = result.result().getList();
                    } else {
                        list = new ArrayList<>();
                    }

                    for (ChatMessage message : messages) {
                        list.add(ChatMessageCodec.INSTANCE.toJson(message));
                    }
                    if (list.size() > channelHistorySize) {
                        list = new ArrayList<>(
                            list.subList(list.size() - channelHistorySize, list.size())
                        );
                    }
                    final JsonArray newJson = new JsonArray(list);
                    map.put(channel, newJson, (putResult) -> {
                        if(putResult.succeeded()) {
                            logConnectionEvent(() -> "Successfully stored messages: "+newJson.encode());
                        } else {
                            log.warn("Error storing messages", putResult.cause());
                        }
                    });
                });
            } else {
                log.warn("Error getting message-map", mapResult.cause());
            }
        });
    }
}
