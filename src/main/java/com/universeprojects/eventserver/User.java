package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.shareddata.Shareable;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

public class User implements Shareable {
    public final String userId;
    public final AtomicLong connectionCounter = new AtomicLong();
    private final Map<String, MessageConsumer<ChatMessage>> channelConsumers = new TreeMap<>();

    public User(String userId) {
        this.userId = userId;
    }

    public void getChannelConsumers(Handler<Map<String, MessageConsumer<ChatMessage>>> mapHandler) {
        synchronized (channelConsumers) {
            mapHandler.handle(channelConsumers);
        }
    }
}
