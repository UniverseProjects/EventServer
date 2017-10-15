package com.universeprojects.eventserver;

import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.vertx.core.json.JsonObject;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class RedisChatCodec implements RedisCodec<String, ChatMessage> {

    private static final StringCodec stringCodec = new StringCodec(Charset.forName("UTF-8"));

    @Override
    public String decodeKey(ByteBuffer bytes) {
        return stringCodec.decodeKey(bytes);
    }

    @Override
    public ChatMessage decodeValue(ByteBuffer bytes) {
        String string = stringCodec.decodeValue(bytes);
        return ChatMessageCodec.INSTANCE.fromJson(new JsonObject(string));
    }

    @Override
    public ByteBuffer encodeKey(String key) {
        return stringCodec.encodeKey(key);
    }

    @Override
    public ByteBuffer encodeValue(ChatMessage value) {
        String string = ChatMessageCodec.INSTANCE.toJson(value).encode();
        return stringCodec.encodeValue(string);
    }
}
