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
            JsonObject json = new JsonObject();
            json.readFromBuffer(0, buffer);
            JsonArray messages = json.getJsonArray("messages");
            Map<String, List<ChatMessage>> userMessages = new LinkedHashMap<>();
            for(Object messageObj : messages) {
                JsonObject messageJson = (JsonObject) messageObj;
                ChatMessage chatMessage = ChatMessageCodec.INSTANCE.fromJson(messageJson);
                if(chatMessage.targetUserIds.isEmpty()) {
                    String channel = verticle.generateChannelAddress(chatMessage.channel);
                    verticle.eventBus.publish(channel, chatMessage);
                } else {
                    for(String userId : chatMessage.targetUserIds) {
                        List<ChatMessage> msgs = userMessages.computeIfAbsent(userId, (key) -> new ArrayList<>());
                        msgs.add(chatMessage);
                    }
                }
            }
            if(!userMessages.isEmpty()) {
                for(Map.Entry<String, List<ChatMessage>> entry : userMessages.entrySet()) {
                    final String userId = entry.getKey();
                    List<ChatMessage> msgs = entry.getValue();
                    verticle.sharedDataService.getGlobalSocketWriterIdsForUser(userId, (writerIds) -> {
                        ChatEnvelope envelope = ChatEnvelope.forMessages(msgs);
                        for (String writerId : writerIds) {
                            verticle.eventBus.publish(writerId, envelope);
                        }
                    });
                }
            }
        });
    }
}
