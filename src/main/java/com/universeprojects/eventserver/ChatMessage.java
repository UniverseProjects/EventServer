package com.universeprojects.eventserver;

import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class ChatMessage {
    public final List<String> targetUserIds = new ArrayList<>();
    public String senderId;
    public String senderDisplayName;
    public String channel;
    public String text;
    public JsonObject additionalData;

    public String toString() {
        return ChatMessageCodec.INSTANCE.toJson(this).encode();
    }
}
