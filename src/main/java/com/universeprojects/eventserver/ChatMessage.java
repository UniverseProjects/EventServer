package com.universeprojects.eventserver;

import io.vertx.core.json.JsonObject;

import java.util.List;

public class ChatMessage {
    List<String> targetUserIds;
    String senderId;
    String senderDisplayName;
    String channel;
    String text;
    JsonObject additionalData;
}
