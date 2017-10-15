package com.universeprojects.eventserver;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.Lock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public class HazelcastHistoryService implements HistoryService {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final EventServerVerticle verticle;

    public HazelcastHistoryService(EventServerVerticle verticle) {
        this.verticle = verticle;
    }

    @Override
    public void storeChatHistory(String channel, int historySize, List<ChatMessage> messages) {
        verticle.sharedDataService.getMessageMap((mapResult) -> {
            if (mapResult.succeeded()) {
                final AsyncMap<String, JsonArray> map = mapResult.result();
                verticle.sharedDataService.getChannelLock(channel, (lockResult) -> {
                    if (lockResult.succeeded()) {
                        final Lock lock = lockResult.result();
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
                            if (list.size() > historySize) {
                                list = new ArrayList<>(
                                    list.subList(list.size() - historySize, list.size())
                                );
                            }
                            final JsonArray newJson = new JsonArray(list);
                            map.put(channel, newJson, (putResult) -> {
                                lock.release();
                                if (putResult.succeeded()) {
                                    verticle.logStorageEvent(() -> "Successfully stored messages: " + newJson.encode());
                                } else {
                                    log.warn("Error storing messages", putResult.cause());
                                }
                            });
                        });
                    } else {
                        log.warn("Unable to get message lock", lockResult.cause());
                    }
                });
            } else {
                log.warn("Error getting message-map", mapResult.cause());
            }
        });
    }

    @Override
    public void fetchHistoryMessages(Set<String> channels, int historySize, BiConsumer<String, List<ChatMessage>> messageHandler) {
        verticle.sharedDataService.getMessageMap((mapResult) -> {
            if (mapResult.succeeded()) {
                final AsyncMap<String, JsonArray> map = mapResult.result();
                for (String channel : channels) {
                    map.get(channel, (result) -> {
                        if (result.succeeded() && result.result() != null) {
                            final JsonArray jsonArray = result.result();
                            if (jsonArray != null && !jsonArray.isEmpty()) {
                                final List<ChatMessage> messages = new ArrayList<>();
                                jsonArray.forEach((messageObj) -> {
                                    ChatMessage message = ChatMessageCodec.INSTANCE.fromJson((JsonObject) messageObj);
                                    if(message.additionalData == null) {
                                        message.additionalData = new JsonObject();
                                    }
                                    message.additionalData.put("__history", true);
                                    messages.add(message);
                                });
                                messageHandler.accept(channel, messages);
                            }
                        }
                    });
                }
            } else {
                log.warn("Error getting message-map", mapResult.cause());
            }
        });
    }
}
