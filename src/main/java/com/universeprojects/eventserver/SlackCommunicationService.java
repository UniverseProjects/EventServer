package com.universeprojects.eventserver;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;

class SlackCommunicationService extends CommunicationService{

    private static final String SERVICE_NAME = "Slack";
    private static final String CONFIG_SLACK_URL = "slack.url";
    private static final String CONFIG_SLACK_USERNAME = "slack.username";
    private static final String CONFIG_SLACK_TOKEN = "slack.token";
    private static final String USER_SLACKBOT = "slackbot";


    private final String slackUrl;
    private final String slackUsername;
    private final String slackToken;

    private final WebClient client;
    private final Router router;

    SlackCommunicationService(EventServerVerticle verticle, Router router) {
        super(verticle, SERVICE_NAME);
        slackUrl = Config.getString(CONFIG_SLACK_URL, null);
        slackUsername = Config.getString(CONFIG_SLACK_USERNAME, null);
        slackToken = Config.getString(CONFIG_SLACK_TOKEN, null);
        client = WebClient.create(verticle.getVertx());
        this.router = router;
    }

    boolean localCanActivateOutgoing() {
        return slackUrl != null;
    }

    boolean localCanActivateIncoming() {
        return slackUrl != null && slackToken != null;
    }

    void activateIncoming() {
        router.route("/slack").handler(this::handleIncomingSlack);
    }

    private void handleIncomingSlack(RoutingContext context) {
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
            channel = incomingChannelMap.getOrDefault(slackChannel, null);
            if (channel == null) { channel = incomingChannelMap.getOrDefault("#" + slackChannel, null); }
            if (channel == null) {
                verticle.logConnectionEvent(() -> "No channel defined for slack-channel "+slackChannel);
                context.response().end();
                return;
            }
            sendInsideMessage(channel, slackChannel, userName, text, new BigDecimal(timestampStr).multiply(BigDecimal.valueOf(1000)).longValue());
            context.response().end();
        });
    }

    void sendOutsideMessage(String sourceChannel, String remoteChannel, String text, String fallbackText, String author, String authorLink, String authorColor, JsonArray additionalFields) {
        JsonObject payload = new JsonObject();
        String channel;
        if(remoteChannel.startsWith("#")) {
            channel = remoteChannel;
        } else {
            channel = "#"+remoteChannel;
        }
        payload.put("channel", channel);
        putIfNotNull(payload, "username", slackUsername);
        JsonArray attachments = new JsonArray();
        payload.put("attachments", attachments);

        JsonObject attachment = new JsonObject();
        attachments.add(attachment);
        putIfNotNull(attachment, "fallback", fallbackText);
        putIfNotNull(attachment, "text", text);
        putIfNotNull(attachment, "author_name", author);
        putIfNotNull(attachment, "author_link", authorLink);
        putIfNotNull(attachment, "color", authorColor);
        if(processHtml) {
            attachment.put("mrkdwn_in", new JsonArray().add("text"));
        }
        if(additionalFields != null) {
            attachment.put("fields", additionalFields.copy());
        }
        verticle.logConnectionEvent(() -> "Sending message from channel "+sourceChannel+" to slack channel "+channel+": "+payload.encode());
        client.postAbs(slackUrl).sendJsonObject(payload, (result) -> {
            if (result.succeeded()) {
                verticle.logConnectionEvent(() -> "Slack send successful");
            } else {
                log.warn("Slack send failed for "+payload.encode(), result.cause());
            }
        });
    }

}
