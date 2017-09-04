package com.universeprojects.eventserver;

import io.vertx.core.MultiMap;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.Lock;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SlackCommunicationService {
    public static final String CONFIG_SLACK_ENABLED = "slack.enabled";
    public static final String CONFIG_SLACK_URL = "slack.url";
    public static final String CONFIG_SLACK_CHANNELS_INCOMING = "slack.channels.incoming";
    public static final String CONFIG_SLACK_CHANNELS_OUTGOING = "slack.channels.outgoing";
    public static final String CONFIG_SLACK_USERNAME = "slack.username";
    public static final String CONFIG_SLACK_TOKEN = "slack.token";
    public static final int FAILOVER_CHECK_TIME = 60 * 1000;
    public static final String DATA_MARKER_FROM_SLACK = "__fromSlack";
    public static final String DATA_AUTHOR_LINK = "slackAuthorLink";
    public static final String DATA_AUTHOR_COLOR = "slackAuthorColor";
    public static final String DATA_ADDITIONAL_FIELDS = "slackAdditionalFields";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final EventServerVerticle verticle;
    private final WebClient client;
    private final boolean slackEnabled;
    private final String slackUrl;
    private final String slackUsername;
    private final String slackToken;
    private final Map<String, String> slackOutgoingChannelMap;
    private final Map<String, String> slackIncomingChannelMap;
    private final Object timerLock = new Object();
    private Lock instanceLock;
    private Long timerId;

    public SlackCommunicationService(EventServerVerticle verticle) {
        this.verticle = verticle;
        this.slackEnabled = Config.getBoolean(CONFIG_SLACK_ENABLED, false);
        this.slackUrl = Config.getString(CONFIG_SLACK_URL, null);
        this.slackUsername = Config.getString(CONFIG_SLACK_USERNAME, null);
        this.slackToken = Config.getString(CONFIG_SLACK_TOKEN, null);
        String outgoingChannelsStr = Config.getString(CONFIG_SLACK_CHANNELS_OUTGOING, null);
        if(outgoingChannelsStr != null) {
            Map<String, String> map = new LinkedHashMap<>();
            JsonObject json = new JsonObject(outgoingChannelsStr);
            for(Map.Entry<String, Object> entry : json.getMap().entrySet()) {
                map.put(entry.getKey(), (String) entry.getValue());
            }
            this.slackOutgoingChannelMap = Collections.unmodifiableMap(map);
        } else {
            this.slackOutgoingChannelMap = Collections.emptyMap();
        }
        String incomingChannelsStr = Config.getString(CONFIG_SLACK_CHANNELS_INCOMING, null);
        if(incomingChannelsStr != null) {
            Map<String, String> map = new LinkedHashMap<>();
            JsonObject json = new JsonObject(incomingChannelsStr);
            for(Map.Entry<String, Object> entry : json.getMap().entrySet()) {
                map.put(entry.getKey(), (String) entry.getValue());
            }
            this.slackIncomingChannelMap = Collections.unmodifiableMap(map);
        } else {
            this.slackIncomingChannelMap = Collections.emptyMap();
        }
        this.client = WebClient.create(verticle.getVertx());
        verticle.logConnectionEvent(() -> "Started SlackService "+toString());
    }

    public boolean canActivateOutgoing() {
        return slackEnabled && slackUrl != null && !slackOutgoingChannelMap.isEmpty();
    }

    public boolean canActivateIncoming() {
        return slackEnabled && slackUrl != null && slackToken != null && !slackIncomingChannelMap.isEmpty();
    }

    public void handleIncomingSlack(RoutingContext context) {
        verticle.logConnectionEvent(() -> "Processing incoming slack message");
        if(context.request().method() != HttpMethod.POST) {
            verticle.logConnectionEvent(() -> "Bad Method on slack message: "+context.request().method());
            context.response().setStatusCode(405);
            context.response().end();
            return;
        }
        context.request().setExpectMultipart(true);
        context.request().endHandler((ignored) -> {
            MultiMap attributes = context.request().formAttributes();
            verticle.logConnectionEvent(() -> "Slack message attributes: "+attributes);
            String token = attributes.get("token");
            String userName = attributes.get("user_name");
            String text = attributes.get("text");
            String timestampStr = attributes.get("timestamp");
            String slackChannel = attributes.get("channel_name");
            if(token == null || userName == null || text == null || slackChannel == null || timestampStr == null) {
                verticle.logConnectionEvent(() -> "Not all required fields on slack message");
                context.response().setStatusCode(400);
                context.response().end();
                return;
            }
            if(!slackToken.equals(token)) {
                verticle.logConnectionEvent(() -> "Bad token on slack message: "+token);
                context.response().setStatusCode(403);
                context.response().end();
                return;
            }
            if(slackUsername != null && slackUsername.equals(userName)) {
                verticle.logConnectionEvent(() -> "Skipping slack user to prevent loops");
                context.response().end();
                return;
            }
            String channel = slackIncomingChannelMap.get(slackChannel);
            if(channel == null) {
                verticle.logConnectionEvent(() -> "No channel defined for slack-channel "+slackChannel);
                context.response().end();
                return;
            }
            String address = verticle.generateChannelAddress(channel);
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.channel = channel;
            chatMessage.senderDisplayName = userName;
            chatMessage.senderId = "slack:"+slackChannel;
            chatMessage.text = text;
            chatMessage.timestamp = new BigDecimal(timestampStr).longValue();
            chatMessage.additionalData = new JsonObject().put(DATA_MARKER_FROM_SLACK, true);
            verticle.logConnectionEvent(() -> "Publishing message from slack channel "+slackChannel+" to channel "+channel+": "+chatMessage);
            verticle.eventBus.publish(address, chatMessage);

            context.response().end();
        });
    }

    @SuppressWarnings("unused")
    public boolean isActive() {
        return canActivateOutgoing() && instanceLock != null;
    }

    public void activate() {
        if(!canActivateOutgoing()) return;
        verticle.logConnectionEvent(() -> "Attempting to acquire slack lock");
        verticle.sharedDataService.getSlackLock((result) -> {
            if(result.succeeded()) {
                instanceLock = result.result();
                log.info("Acquired Slack lock - activating message-service");
                cancelTimer();
                setupHandlers();
            } else {
                log.info("Failed to acquire slack lock", result.cause());
                setupTimer();
            }
        });
    }

    public void setupRoute(Router router) {
        if(canActivateIncoming()) {
            router.route("/slack").handler(this::handleIncomingSlack);
        }
    }

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
        for(Map.Entry<String, String> entry : slackOutgoingChannelMap.entrySet()) {
            String channel = entry.getKey();
            String slackChannel = entry.getValue();
            verticle.eventBus.<ChatMessage>consumer(verticle.generateChannelAddress(channel),
                    (message) -> processChannelMessage(message, slackChannel)
            );
        }
    }

    private void processChannelMessage(Message<ChatMessage> message, String slackChannel) {
        ChatMessage chatMessage = message.body();
        if(chatMessage.text == null) {
            return;
        }
        JsonObject additionalData = chatMessage.additionalData;
        String authorLink = null;
        String authorColor = null;
        JsonArray additionalFields = null;
        if(additionalData != null)  {
            Boolean fromSlack = additionalData.getBoolean(DATA_MARKER_FROM_SLACK, false);
            if(fromSlack != null && fromSlack) {
                return;//Prevent loops
            }
            authorLink = additionalData.getString(DATA_AUTHOR_LINK);
            authorColor = additionalData.getString(DATA_AUTHOR_COLOR);
            additionalFields = additionalData.getJsonArray(DATA_ADDITIONAL_FIELDS);
        }
        JsonObject payload = new JsonObject();
        payload.put("channel", slackChannel);
        putIfNotNull(payload, "username", slackUsername);
        JsonArray attachments = new JsonArray();
        payload.put("attachments", attachments);

        JsonObject attachment = new JsonObject();
        attachments.add(attachment);
        putIfNotNull(attachment, "fallback", chatMessage.text);
        putIfNotNull(attachment, "text", chatMessage.text);
        putIfNotNull(attachment, "author_name", chatMessage.senderDisplayName);
        putIfNotNull(attachment, "author_link", authorLink);
        putIfNotNull(attachment, "color", authorColor);
        if(additionalFields != null) {
            attachment.put("fields", additionalFields.copy());
        }
        verticle.logConnectionEvent(() -> "Sending message from channel "+chatMessage.channel+" to slack channel "+slackChannel+": "+payload.encode());
        client.postAbs(slackUrl).sendJsonObject(payload, (result) -> {
            if (result.succeeded()) {
                verticle.logConnectionEvent(() -> "Slack send successful");
            } else {
                log.warn("Slack send failed for "+payload.encode(), result.cause());
            }
        });
    }


    private static void putIfNotNull(JsonObject object, String key, String data) {
        if(data != null) {
            object.put(key, data);
        }
    }

    @Override
    public String toString() {
        return "SlackCommunicationService{" +
            "slackEnabled=" + slackEnabled +
            ", slackUrl='" + slackUrl + '\'' +
            ", slackUsername='" + slackUsername + '\'' +
            ", slackToken='" + slackToken + '\'' +
            ", slackOutgoingChannelMap=" + slackOutgoingChannelMap +
            ", slackIncomingChannelMap=" + slackIncomingChannelMap +
            '}';
    }
}
