package com.universeprojects.eventserver;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.eventbus.impl.codecs.JsonObjectMessageCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;

public class ChatMessageCodec implements MessageCodec<ChatMessage, ChatMessage> {

    public static final ChatMessageCodec INSTANCE = new ChatMessageCodec();

    private final JsonObjectMessageCodec jsonCodec = new JsonObjectMessageCodec();

    public JsonObject toJson(ChatMessage chatMessage) {
        return toJson(chatMessage, true);
    }

    public JsonObject toJson(ChatMessage chatMessage, boolean includeTargetUsers) {
        JsonObject json = new JsonObject();
        json.put("senderUserId", chatMessage.senderId);
        json.put("senderDisplayName", chatMessage.senderDisplayName);
        if(includeTargetUsers) {
            json.put("targetUserIds", new JsonArray(new ArrayList<>(chatMessage.targetUserIds)));
        }
        json.put("channel", chatMessage.channel);
        json.put("text", chatMessage.text);
        json.put("timestamp", chatMessage.timestamp);
        if(chatMessage.additionalData != null) {
            json.put("additionalData", chatMessage.additionalData.copy());
        }
        return json;
    }

    public ChatMessage fromJson(JsonObject json) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.senderId = json.getString("senderId");
        chatMessage.senderDisplayName = json.getString("senderDisplayName");

        final JsonArray targetUserIds = json.getJsonArray("targetUserIds");
        if(targetUserIds != null) {
            //noinspection unchecked
            chatMessage.targetUserIds.addAll(targetUserIds.getList());
        }

        chatMessage.channel = json.getString("channel");
        chatMessage.text = json.getString("text");
        chatMessage.timestamp = json.getLong("timestamp");
        chatMessage.additionalData = json.getJsonObject("additionalData");
        return chatMessage;
    }

    @Override
    public void encodeToWire(Buffer buffer, ChatMessage chatMessage) {
        jsonCodec.encodeToWire(buffer, toJson(chatMessage));
    }

    @Override
    public ChatMessage decodeFromWire(int pos, Buffer buffer) {
        JsonObject json = jsonCodec.decodeFromWire(pos, buffer);
        return fromJson(json);
    }

    @Override
    public ChatMessage transform(ChatMessage chatMessage) {
        return fromJson(toJson(chatMessage));
    }

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public byte systemCodecID() {
        return -1;
    }
}
