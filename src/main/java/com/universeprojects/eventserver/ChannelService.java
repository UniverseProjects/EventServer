package com.universeprojects.eventserver;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

public class ChannelService {
    private final Map<String, ChannelSubscription> channelSubscriptions = new TreeMap<>();
    private final ReentrantLock channelSubscriptionLock = new ReentrantLock();
    private final EventServerVerticle verticle;

    public ChannelService(EventServerVerticle verticle) {
        this.verticle = verticle;
    }

    public Set<String> updateSubscriptions(User user, Collection<String> newChannels) {
        Set<String> addedChannels = new LinkedHashSet<>();
        addedChannels.addAll(newChannels);
        addedChannels.removeAll(user.channelSubscriptions.keySet());

        Set<String> removedChannels = new LinkedHashSet<>();
        removedChannels.addAll(user.channelSubscriptions.keySet());
        removedChannels.removeAll(newChannels);

        if(addedChannels.isEmpty() && removedChannels.isEmpty()) return addedChannels;

        channelSubscriptionLock.lock();
        try {
            for(String channel : removedChannels) {
                final ChannelSubscription subscription = user.channelSubscriptions.remove(channel);
                subscription.users.remove(user);
                if(subscription.users.isEmpty()) {
                    unsubscribe(subscription);
                }
            }
            for(String channel : addedChannels) {
                ChannelSubscription subscription = channelSubscriptions.get(channel);
                if(subscription == null) {
                    final ChannelSubscription newSubscription = new ChannelSubscription(channel);
                    newSubscription.messageConsumer = verticle.eventBus.consumer(
                        verticle.generateChannelAddress(channel),
                        (message) -> processChannelMessage(newSubscription, message));
                    channelSubscriptions.put(channel, newSubscription);
                    subscription = newSubscription;
                }
                subscription.users.add(user);
                user.channelSubscriptions.put(channel, subscription);
            }
        } finally {
            channelSubscriptionLock.unlock();
        }
        return addedChannels;
    }

    private void processChannelMessage(ChannelSubscription subscription, Message<ChatMessage> message) {
        JsonObject messageJson = ChatEnvelope.forMessage(message.body()).toJson();
        Buffer buffer = messageJson.toBuffer();
        subscription.users.parallelStream().flatMap(user -> user.sockets.stream()).forEach(socket -> socket.write(buffer));
    }


    public void unsubscribe(ChannelSubscription subscription) {
        if(!channelSubscriptionLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("ChannelSubscriptionLock needs to be held by this thread");
        }
        subscription.messageConsumer.unregister();
        channelSubscriptions.remove(subscription.channel);
    }

    //new userId - no user - create user-object - add channel-subscription / add user to channel-subscription
    //existing userId - get user -> add socket to user
    //user changes userId -> ?
}
