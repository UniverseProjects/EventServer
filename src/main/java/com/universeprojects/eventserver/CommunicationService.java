package com.universeprojects.eventserver;

import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.Lock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public abstract class CommunicationService {

    private static final String CONFIG_ENABLED = "enabled";
    private static final String CONFIG_PROCESS_HTML = "process.html";
    private static final String CONFIG_CHANNELS_INCOMING = "channels.incoming";
    private static final String CONFIG_CHANNELS_OUTGOING = "channels.outgoing";
    private static final int FAILOVER_CHECK_TIME = 60 * 1000;
    protected static final String DATA_MARKER_FROM = "__from";
    private static final String DATA_AUTHOR_LINK = "AuthorLink";
    private static final String DATA_AUTHOR_COLOR = "AuthorColor";
    private static final String DATA_ADDITIONAL_FIELDS = "AdditionalFields";

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final EventServerVerticle verticle;
    protected final String serviceName;
    protected final boolean enabled;
    protected final Map<String, String> outgoingChannelMap;
    protected final Map<String, String> incomingChannelMap;
    private final Object timerLock = new Object();
    private Lock instanceLock;
    private Long timerId;
    protected final boolean processHtml;

    private String prefixConfig(String config) {
        return serviceName.toLowerCase() + "." + config;
    }

    private Map<String, String> processMap(String channels) {
        if(channels != null) {
            Map<String, String> map = new LinkedHashMap<>();
            JsonObject json = new JsonObject(channels);
            for(Map.Entry<String, Object> entry : json.getMap().entrySet()) {
                map.put(entry.getKey(), (String) entry.getValue());
            }
            return Collections.unmodifiableMap(map);
        } else {
            return Collections.emptyMap();
        }
    }

    public CommunicationService(EventServerVerticle verticle, String serviceName) {
        this.verticle = Objects.requireNonNull(verticle);
        this.serviceName = Objects.requireNonNull(serviceName);
        this.enabled = Config.getBoolean(prefixConfig(CONFIG_ENABLED), false);
        this.processHtml = Config.getBoolean(prefixConfig(CONFIG_PROCESS_HTML), false);
        this.outgoingChannelMap = processMap(Config.getString(prefixConfig(CONFIG_CHANNELS_OUTGOING), null));
        this.incomingChannelMap = processMap(Config.getString(prefixConfig(CONFIG_CHANNELS_INCOMING), null));
        verticle.logConnectionEvent(() -> "Started Service " + toString());
    }

    abstract boolean localCanActivateOutgoing();

    private boolean canActivateOutgoing() {
        return enabled && !outgoingChannelMap.isEmpty() && localCanActivateOutgoing();
    }

    abstract boolean localCanActivateIncoming();

    private boolean canActivateIncoming() {
        return enabled && !incomingChannelMap.isEmpty() && localCanActivateIncoming();
    }

    protected void sendInsideMessage(String insideChannel, String outsideChannel, String userName, String text, Long timestamp) {
        String address = verticle.generateChannelAddress(insideChannel);
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.channel = insideChannel;
        chatMessage.senderDisplayName = userName;
        chatMessage.senderUserId = "remote:"+outsideChannel;
        chatMessage.text = text;
        chatMessage.timestamp = timestamp;
        chatMessage.additionalData = new JsonObject().put(DATA_MARKER_FROM + serviceName , true);
        EscapingService.INSTANCE.escapeHtml(chatMessage, false);
        verticle.logConnectionEvent(() -> "Publishing message from remote channel "+outsideChannel+" to channel "+insideChannel+": "+chatMessage);
        verticle.eventBus.publish(address, chatMessage);
        verticle.storeChatHistory(insideChannel, Collections.singletonList(chatMessage));
    }

    @SuppressWarnings("unused")
    public boolean isActive() {
        return (canActivateIncoming() || canActivateOutgoing()) && instanceLock != null;
    }

    public void activate() {
        if (canActivateIncoming() || canActivateOutgoing()) {
            verticle.logConnectionEvent(() -> "Attempting to acquire lock for " + serviceName);
            verticle.sharedDataService.getCommunicationsLock(serviceName.toLowerCase(), (result) -> {
                if (result.succeeded()) {
                    instanceLock = result.result();
                    log.info("Acquired " + serviceName + " lock - activating message-service");
                    cancelTimer();
                    if (canActivateOutgoing()) {
                        activateOutgoing();
                    }
                    if (canActivateIncoming()) {
                        activateIncoming();
                    }
                } else {
                    log.info("Failed to acquire " + serviceName + " lock", result.cause());
                    setupTimer();
                }
            });
        }
    }

    protected void activateOutgoing() {
        setupHandlers();
    }

    abstract void activateIncoming();

    private void setupTimer() {
        synchronized (timerLock) {
            if (timerId != null) return;
            verticle.logConnectionEvent(() -> "Unable to acquire slack lock - setting up timer");
            timerId = verticle.getVertx().setPeriodic(FAILOVER_CHECK_TIME, (ignored) -> activate());
        }
    }

    private void cancelTimer() {
        synchronized (timerLock) {
            if (timerId == null) return;
            verticle.getVertx().cancelTimer(timerId);
            timerId = null;
        }
    }

    private void setupHandlers() {
        for(Map.Entry<String, String> entry : outgoingChannelMap.entrySet()) {
            String insideChannel = entry.getKey();
            String outsideChannel = entry.getValue();
            verticle.eventBus.<ChatMessage>consumer(verticle.generateChannelAddress(insideChannel),
                    (message) -> processChannelMessage(message, outsideChannel)
            );
        }
    }

    private void processChannelMessage(Message<ChatMessage> message, String remoteChannel) {
        ChatMessage chatMessage = message.body();
        String channel = chatMessage.channel;
        if(chatMessage.text == null) {
            return;
        }
        JsonObject additionalData = chatMessage.additionalData;
        String authorLink = null;
        String authorColor = null;
        JsonArray additionalFields = null;
        if(additionalData != null)  {
            Boolean fromUs = additionalData.getBoolean(DATA_MARKER_FROM + serviceName, false);
            if(fromUs != null && fromUs) {
                return;//Prevent loops
            }
            authorLink = additionalData.getString(serviceName + DATA_AUTHOR_LINK);
            authorColor = additionalData.getString(serviceName + DATA_AUTHOR_COLOR);
            additionalFields = additionalData.getJsonArray(serviceName + DATA_ADDITIONAL_FIELDS);
        }

        String text = chatMessage.text;
        String fallbackText = chatMessage.text; //TODO strip markdown

        sendOutsideMessage(channel, remoteChannel, text, fallbackText, chatMessage.senderDisplayName, authorLink, authorColor, additionalFields);


    }

    abstract void sendOutsideMessage(String sourceChannel, String remoteChannel, String text, String fallbackText, String author, String authorLink, String authorColor, JsonArray additionalFields);

    static void putIfNotNull(JsonObject object, String key, String data) {
        if(data != null) {
            object.put(key, data);
        }
    }
}
