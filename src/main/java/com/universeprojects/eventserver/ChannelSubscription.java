package com.universeprojects.eventserver;

import io.vertx.core.eventbus.MessageConsumer;

import java.util.LinkedHashSet;
import java.util.Set;

public class ChannelSubscription {
    public final String channel;
    public final Set<User> users = new LinkedHashSet<>();
    public MessageConsumer<ChatMessage> messageConsumer;

    public ChannelSubscription(String channel) {
        this.channel = channel;
    }
}
