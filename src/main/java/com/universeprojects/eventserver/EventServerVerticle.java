package com.universeprojects.eventserver;


import io.prometheus.client.hotspot.DefaultExports;
import io.prometheus.client.vertx.MetricsHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.sstore.LocalSessionStore;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class EventServerVerticle extends AbstractVerticle {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String CONFIG_MODE = "server.mode";
    public static final String CONFIG_PORT = "server.port";
    public static final String CONFIG_CORS_ORIGINS = "cors.origins";
    public static final String CONFIG_LOG_CONNECTIONS = "log.connections";
    public static final String CONFIG_LOG_STORAGE = "log.storage";
    public static final String CONFIG_CHANNEL_HISTORY_SIZE = "channel.history.size";
    public static final String CONFIG_REDIS_ENABLED = "redis.enabled";

    private static final int DEFAULT_HISTORY_SIZE = 100;

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
    public DiscordCommunicationService discordCommunicationService;
    public HistoryService historyService;
    public ChannelService channelService;
    public UserService userService;
    public SessionService sessionService;
    private boolean logConnections = false;
    private boolean logStorage = false;
    private int channelHistorySize = DEFAULT_HISTORY_SIZE;

    @Override
    public void start() {
        logConnections = Config.getBoolean(CONFIG_LOG_CONNECTIONS, false);
        logStorage = Config.getBoolean(CONFIG_LOG_STORAGE, false);
        final String corsOrigins = Config.getString(CONFIG_CORS_ORIGINS, "*");
        final int port = Config.getInt(CONFIG_PORT, 6969);
        serverMode = Config.getEnum(CONFIG_MODE, ServerMode.class, ServerMode.PROD);
        channelHistorySize = Config.getInt(CONFIG_CHANNEL_HISTORY_SIZE, DEFAULT_HISTORY_SIZE);
        final boolean enableRedis = Config.getBoolean(CONFIG_REDIS_ENABLED, false);
        if(enableRedis) {
            historyService = new RedisHistoryService();
        } else {
            historyService = new HazelcastHistoryService(this);
        }
        this.userService = new UserService(this);
        this.channelService = new ChannelService(this);
        this.sessionService = new SessionService();
        final HttpServer server = vertx.createHttpServer();
        final Router router = Router.router(vertx);

        DefaultExports.initialize();

        router.route("/healthcheck").handler(new HealthCheckHandler(this));
        router.route("/version").blockingHandler(new VersionHandler());
        router.route("/metrics").handler(new MetricsHandler());

        router.route().handler(CookieHandler.create());
        router.route().handler(CorsHandler.create(corsOrigins));
        router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)).setNagHttps(false));


        if (serverMode == ServerMode.TEST || serverMode == ServerMode.TEST_CLIENT) {
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

        final Route socketRoute = router.route("/socket/*");
        socketRoute.handler(sockJSHandler);

        final ApiAuthHandler apiAuthHandler = new ApiAuthHandler();
        router.route("/send").handler(apiAuthHandler);
        router.route("/updateUsers").handler(apiAuthHandler);

        router.route("/send").handler(new IncomingMessageHandler(this));
        router.route("/updateUsers").handler(new UpdateUsersHandler(this));

        slackCommunicationService = new SlackCommunicationService(this);
        slackCommunicationService.activate();
        slackCommunicationService.setupRoute(router);

        discordCommunicationService = new DiscordCommunicationService(this);
        discordCommunicationService.activate();

        server.requestHandler(router::accept).listen(port, "0.0.0.0");
        log.info("Server started up at http://localhost:" + port);
    }

    public String generateChannelAddress(String channel) {
        return "channel." + channel;
    }

    public String generateUserUpdateAddress(String userId) {
        return "user.update." + userId;
    }

    public String generatePrivateMessageAddress(String userId) {
        return "user.private." + userId;
    }

    public boolean shouldLogConnections() {
        return logConnections;
    }

    public boolean shouldLogStorage() {
        return logStorage;
    }

    public void logConnectionEvent(Supplier<String> messageSupplier) {
        if (shouldLogConnections()) {
            log.info(messageSupplier.get());
        }
    }

    public void logStorageEvent(Supplier<String> messageSupplier) {
        if (shouldLogStorage()) {
            log.info(messageSupplier.get());
        }
    }

    public void storeChatHistory(String channel, List<ChatMessage> messages) {
        if (!shouldStoreMessages(channel)) {
            return;
        }
        historyService.storeChatHistory(channel, channelHistorySize, messages);
    }

    public void fetchHistoryMessages(Set<String> channelNames, BiConsumer<String, List<ChatMessage>> messageHandler) {
        Set<String> channelMessagesWithHistory = channelNames.stream().filter(this::shouldStoreMessages).collect(Collectors.toSet());
        if(channelMessagesWithHistory.isEmpty()) return;
        historyService.fetchHistoryMessages(channelNames, channelHistorySize, messageHandler);
    }

    public boolean shouldStoreMessages(String channel) {
        return !channel.startsWith("!");
    }
}
