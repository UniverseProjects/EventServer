package com.universeprojects.eventserver;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.eventbus.impl.codecs.JsonObjectMessageCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;

public class ChatEnvelopeCodec implements MessageCodec<ChatEnvelope, ChatEnvelope> {

    public static final ChatEnvelopeCodec INSTANCE = new ChatEnvelopeCodec();

    private final JsonObjectMessageCodec jsonCodec = new JsonObjectMessageCodec();

    @Override
    public void encodeToWire(Buffer buffer, ChatEnvelope chatEnvelope) {
        jsonCodec.encodeToWire(buffer, chatEnvelope.toJson());
    }

    @Override
    public ChatEnvelope decodeFromWire(int pos, Buffer buffer) {
        JsonObject json = jsonCodec.decodeFromWire(pos, buffer);
        return ChatEnvelope.fromJson(json);
    }

    @Override
    public ChatEnvelope transform(ChatEnvelope chatEnvelope) {
        return chatEnvelope.copy();
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
