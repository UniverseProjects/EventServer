package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IncomingMessageHandler implements Handler<RoutingContext> {

    private final EventServerVerticle verticle;


    public IncomingMessageHandler(EventServerVerticle verticle) {
        this.verticle = verticle;
    }

    @Override
    public void handle(RoutingContext context) {
        if(context.request().method() != HttpMethod.POST) {
            context.response().setStatusCode(405);
            context.response().end();
            return;
        }

        context.request().bodyHandler((buffer) -> {
            final JsonObject json = buffer.toJsonObject();
            final JsonArray messages = json.getJsonArray("messages");
            final Map<String, List<ChatMessage>> userMessages = new LinkedHashMap<>();
            final Map<String, List<ChatMessage>> channelMessages = new LinkedHashMap<>();
            parseAndCategorizeMessages(messages, userMessages, channelMessages);
            processChannelMessages(channelMessages);
            processUserMessages(userMessages);
            context.response().end();
        });
    }

    private void parseAndCategorizeMessages(JsonArray messages, Map<String, List<ChatMessage>> userMessages, Map<String, List<ChatMessage>> channelMessages) {
        for(Object messageObj : messages) {
            JsonObject messageJson = (JsonObject) messageObj;
            ChatMessage chatMessage = ChatMessageCodec.INSTANCE.fromJson(messageJson);
            if(chatMessage.timestamp == null) {
                chatMessage.timestamp = System.currentTimeMillis();
            }
            if(chatMessage.targetUserIds.isEmpty()) {
                List<ChatMessage> msgs = channelMessages.computeIfAbsent(chatMessage.channel, (key) -> new ArrayList<>());
                msgs.add(chatMessage);
            } else {
                for(String userId : chatMessage.targetUserIds) {
                    List<ChatMessage> msgs = userMessages.computeIfAbsent(userId, (key) -> new ArrayList<>());
                    msgs.add(chatMessage);
                }
            }
        }
    }

    private void processChannelMessages(Map<String, List<ChatMessage>> messageMap) {
        for(Map.Entry<String, List<ChatMessage>> entry : messageMap.entrySet()) {
            final String channel = entry.getKey();
            final List<ChatMessage> messages = entry.getValue();
            String address = verticle.generateChannelAddress(channel);
            for(ChatMessage chatMessage : messages) {
                verticle.logConnectionEvent(() -> "Processing message for channel " + channel + ": " + chatMessage.text);
                verticle.eventBus.publish(address, chatMessage);
            }
            verticle.storeChatHistory(channel, messages);
        }
    }

    private void processUserMessages(Map<String, List<ChatMessage>> messageMap) {
        for(Map.Entry<String, List<ChatMessage>> entry : messageMap.entrySet()) {
            final String userId = entry.getKey();
            List<ChatMessage> msgs = entry.getValue();
            if(verticle.shouldLogConnections()) {
                for(ChatMessage chatMessage : msgs) {
                    verticle.logConnectionEvent(() -> "Processing direct message for user " + userId + ": " + chatMessage.text);
                }
            }
            String address = verticle.generatePrivateMessageAddress(userId);
            for(ChatMessage message : msgs) {
                verticle.eventBus.publish(address, message);
            }
        }
    }
}
