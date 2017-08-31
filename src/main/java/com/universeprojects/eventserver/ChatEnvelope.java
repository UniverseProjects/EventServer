package com.universeprojects.eventserver;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Collections;
import java.util.List;

public class ChatEnvelope {
    private final List<ChatMessage> messages;
    private final String error;

    private ChatEnvelope(List<ChatMessage> messages, String error) {
        this.messages = messages;
        this.error = error;
    }

    public static ChatEnvelope forMessages(List<ChatMessage> messages) {
        return new ChatEnvelope(messages, null);
    }

    public static ChatEnvelope forMessage(ChatMessage message) {
        return new ChatEnvelope(Collections.singletonList(message), null);
    }

    public static ChatEnvelope forError(String errorMessage) {
        return new ChatEnvelope(null, errorMessage);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        if(error != null) {
            json.put("error", error);
        }
        if(messages != null) {
            JsonArray messagesJson = new JsonArray();
            for(ChatMessage chatMessage : messages) {
                messagesJson.add(ChatMessageCodec.INSTANCE.toJson(chatMessage, false));
            }
            json.put("messages", messagesJson);
        }
        return json;
    }
}
