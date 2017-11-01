package com.universeprojects.eventserver;

import io.prometheus.client.Gauge;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.shareddata.Shareable;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

public class User implements Shareable {
    public final String userId;
    public final Map<String, ChannelSubscription> channelSubscriptions = new TreeMap<>();
    public final Queue<SockJSSocket> sockets = new ConcurrentLinkedQueue<>();
    public final Map<String, Set<SockJSSocket>> sessionIdToSocketMap = new LinkedHashMap<>();
    public MessageConsumer<JsonArray> updateConsumer;
    public MessageConsumer<ChatMessage> privateMessageConsumer;
    private boolean removed = false;
    private final ReentrantLock lock = new ReentrantLock();
    private static final Gauge GAUGE_NUM_USERS = Gauge.build().name("users_total").help("Number of total users").register();
    private static final Gauge GAUGE_USER_CONNECTIONS = Gauge.build().name("user_connections").help("Connections per user").labelNames("user").register();

    public boolean isRemoved() {
        return removed;
    }

    public void remove() {
        if(updateConsumer != null) {
            updateConsumer.unregister();
        }
        if(privateMessageConsumer != null) {
            privateMessageConsumer.unregister();
        }
        GAUGE_NUM_USERS.dec();
        GAUGE_USER_CONNECTIONS.remove(userId);
        removed = true;
    }

    public User(String userId) {
        this.userId = userId;
        GAUGE_NUM_USERS.inc();
    }

    public void executeLocked(Consumer<User> consumer) {
        lock.lock();
        try {
            consumer.accept(this);
        } finally {
            lock.unlock();
        }
    }

    public <T> T executeLockedReturning(Function<User, T> consumer) {
        lock.lock();
        try {
            return consumer.apply(this);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return "User{"+userId+"]";
    }

    public void registerSocket(SockJSSocket socket) {
        enforceLockHeld();
        final String sessionId = socket.webSession().id();
        Set<SockJSSocket> socketSet = sessionIdToSocketMap.get(sessionId);
        if(socketSet == null) {
            socketSet = new LinkedHashSet<>();
            sessionIdToSocketMap.put(sessionId, socketSet);
        }
        socketSet.add(socket);
        sockets.add(socket);
        GAUGE_USER_CONNECTIONS.labels(userId).inc();
    }

    public void removeSocket(SockJSSocket socket) {
        enforceLockHeld();
        final String sessionId = socket.webSession().id();
        final Set<SockJSSocket> socketSet = sessionIdToSocketMap.get(sessionId);
        if(socketSet != null) {
            socketSet.remove(socket);
        }
        sockets.remove(socket);
        GAUGE_USER_CONNECTIONS.labels(userId).dec();
    }

    public Set<SockJSSocket> removeSession(String sessionId) {
        enforceLockHeld();
        final Set<SockJSSocket> socketSet = sessionIdToSocketMap.get(sessionId);
        if(socketSet == null) {
            return Collections.emptySet();
        }
        sockets.removeAll(socketSet);
        return socketSet;
    }

    public void enforceLockHeld() {
        if(!isLockedByCurrentThread()) {
            throw new IllegalStateException("User lock needs to be held");
        }
    }

    public boolean isLockedByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }
}
