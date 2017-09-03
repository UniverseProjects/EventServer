package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.ext.web.RoutingContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IncomingMessageHandler implements Handler<RoutingContext> {

    public static final String CONFIG_CHANNEL_HISTORY_SIZE = "channel.history.size";
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final EventServerVerticle verticle;
    private final int channelHistorySize;

    public IncomingMessageHandler(EventServerVerticle verticle) {
        this.verticle = verticle;
        this.channelHistorySize = Config.getInt(CONFIG_CHANNEL_HISTORY_SIZE, 200);
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
            verticle.sharedDataService.getMessageMap((mapResult) -> {
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
                        map.put(channel, new JsonArray(list), null);
                    });
                } else {
                    log.warn("Error getting message-map", mapResult.cause());
                }
            });
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
            verticle.sharedDataService.getGlobalSocketWriterIdsForUser(userId, (writerIds) -> {
                ChatEnvelope envelope = ChatEnvelope.forMessages(msgs);
                Buffer buffer  = Buffer.buffer(envelope.toJson().encode());
                for (String writerId : writerIds) {
                    verticle.eventBus.publish(writerId, buffer);
                }
            });
        }
    }
}
