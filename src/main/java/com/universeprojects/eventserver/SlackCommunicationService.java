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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SlackCommunicationService {
    public static final String CONFIG_SLACK_ENABLED = "slack.enabled";
    public static final String CONFIG_SLACK_URL = "slack.url";
    public static final String CONFIG_SLACK_CHANNELS_INCOMING = "slack.channels.incoming";
    public static final String CONFIG_SLACK_CHANNELS_OUTGOING = "slack.channels.outgoing";
    public static final String CONFIG_SLACK_USERNAME = "slack.username";
    public static final String CONFIG_SLACK_TOKEN = "slack.token";
    public static final String CONFIG_SLACK_PROCESS_HTML = "slack.process.html";
    public static final int FAILOVER_CHECK_TIME = 60 * 1000;
    public static final String DATA_MARKER_FROM_SLACK = "__fromSlack";
    public static final String DATA_AUTHOR_LINK = "slackAuthorLink";
    public static final String DATA_AUTHOR_COLOR = "slackAuthorColor";
    public static final String DATA_ADDITIONAL_FIELDS = "slackAdditionalFields";
    public static final String USER_SLACKBOT = "slackbot";

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
    private boolean processHtml;

    public SlackCommunicationService(EventServerVerticle verticle) {
        this.verticle = verticle;
        this.slackEnabled = Config.getBoolean(CONFIG_SLACK_ENABLED, false);
        this.slackUrl = Config.getString(CONFIG_SLACK_URL, null);
        this.slackUsername = Config.getString(CONFIG_SLACK_USERNAME, null);
        this.slackToken = Config.getString(CONFIG_SLACK_TOKEN, null);
        this.processHtml = Config.getBoolean(CONFIG_SLACK_PROCESS_HTML, false);
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
            verticle.logConnectionEvent(() -> {
                JsonArray formJson = new JsonArray();
                for(Map.Entry<String, String> entry : attributes.entries()) {
                    JsonObject json = new JsonObject().put("key", entry.getKey()).put("val", entry.getValue());
                    formJson.add(json);
                }
                return "Slack message attributes: "+formJson.encode();
            });
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
            if(USER_SLACKBOT.equals(userName)) {
                verticle.logConnectionEvent(() -> "Skipping bots");
                context.response().end();
                return;
            }
            if(text.isEmpty()) {
                verticle.logConnectionEvent(() -> "Skipping empty text");
                context.response().end();
                return;
            }
            String channel;
            if(slackIncomingChannelMap.containsKey(slackChannel)) {
                channel = slackIncomingChannelMap.get(slackChannel);
            } else if(slackIncomingChannelMap.containsKey("#"+slackChannel)) {
                channel = slackIncomingChannelMap.get("#"+slackChannel);
            } else {
                channel = null;
            }
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
            chatMessage.timestamp = new BigDecimal(timestampStr).multiply(BigDecimal.valueOf(1000)).longValue();
            chatMessage.additionalData = new JsonObject().put(DATA_MARKER_FROM_SLACK, true);
            verticle.logConnectionEvent(() -> "Publishing message from slack channel "+slackChannel+" to channel "+channel+": "+chatMessage);
            verticle.eventBus.publish(address, chatMessage);
            verticle.storeMessages(channel, Collections.singletonList(chatMessage));

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

        String text = processText(chatMessage.text, processHtml);
        String fallbackText = processText(chatMessage.text, false);


        JsonObject payload = new JsonObject();
        String channel;
        if(slackChannel.startsWith("#")) {
            channel = slackChannel;
        } else {
            channel = "#"+slackChannel;
        }
        payload.put("channel", channel);
        putIfNotNull(payload, "username", slackUsername);
        JsonArray attachments = new JsonArray();
        payload.put("attachments", attachments);

        JsonObject attachment = new JsonObject();
        attachments.add(attachment);
        putIfNotNull(attachment, "fallback", fallbackText);
        putIfNotNull(attachment, "text", text);
        putIfNotNull(attachment, "author_name", chatMessage.senderDisplayName);
        putIfNotNull(attachment, "author_link", authorLink);
        putIfNotNull(attachment, "color", authorColor);
        if(processHtml) {
            attachment.put("mrkdwn_in", new JsonArray().add("text"));
        }
        if(additionalFields != null) {
            attachment.put("fields", additionalFields.copy());
        }
        verticle.logConnectionEvent(() -> "Sending message from channel "+chatMessage.channel+" to slack channel "+channel+": "+payload.encode());
        client.postAbs(slackUrl).sendJsonObject(payload, (result) -> {
            if (result.succeeded()) {
                verticle.logConnectionEvent(() -> "Slack send successful");
            } else {
                log.warn("Slack send failed for "+payload.encode(), result.cause());
            }
        });
    }

    private final Pattern linkPattern = Pattern.compile("<\\s*a\\s+href=\"([^\"]*)\"\\s*>([^<]+)<\\/a\\s*>");
    private final Pattern boldPattern = Pattern.compile("<\\s*b\\s*>([^<]+)<\\/b\\s*>");
    private final Pattern strongPattern = Pattern.compile("<\\s*strong\\s*>([^<]+)<\\/strong\\s*>");
    private final Pattern italicPattern = Pattern.compile("<\\s*i\\s*>([^<]+)<\\/i\\s*>");
    private final Pattern emPattern = Pattern.compile("<\\s*em\\s*>([^<]+)<\\/em\\s*>");

    private String processText(final String str, final boolean translateHtml) {
        String text = str;
        final Matcher linkMatcher = linkPattern.matcher(text);
        if(translateHtml) {
            text = linkMatcher.replaceAll("!!l!!$1|$2!!g!!");
        } else {
            text = linkMatcher.replaceAll("$2");
        }

        final Matcher boldMatcher = boldPattern.matcher(text);
        if(translateHtml) {
            text = boldMatcher.replaceAll("*$1*");
        } else {
            text = boldMatcher.replaceAll("$1");
        }

        final Matcher strongMatcher = strongPattern.matcher(text);
        if(translateHtml) {
            text = strongMatcher.replaceAll("*$1*");
        } else {
            text = strongMatcher.replaceAll("$1");
        }

        final Matcher italicMatcher = italicPattern.matcher(text);
        if(translateHtml) {
            text = italicMatcher.replaceAll("_$1_");
        } else {
            text = italicMatcher.replaceAll("$1");
        }

        final Matcher emMatcher = emPattern.matcher(text);
        if(translateHtml) {
            text = emMatcher.replaceAll("_$1_");
        } else {
            text = emMatcher.replaceAll("$1");
        }

        if(translateHtml) {
            text = text.replaceAll("<\\s*br\\s*/?>", "\\n");
        } else {
            text = text.replaceAll("<\\s*br\\s*/?>", "");
        }

        text = escapeForSlack(text);

        if(processHtml) {
            text = text.replaceAll("!!l!!", "<");
            text = text.replaceAll("!!g!!", ">");
        }

        return text;
    }

    private String escapeForSlack(String str) {
        return str.
            replaceAll("&", "%amp;").
            replaceAll("<", "&lt;").
            replaceAll(">","&gt;");
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
