package com.universeprojects.eventserver;

import io.vertx.core.json.JsonObject;

import java.util.List;

public class ChatMessage {
    public List<String> targetUserIds;
    public String senderId;
    public String senderDisplayName;
    public String channel;
    public String text;
    public JsonObject additionalData;
}
