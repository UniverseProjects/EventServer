package com.universeprojects.eventserver;

import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.shareddata.Shareable;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

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
        removed = true;
    }

    private final ReentrantLock lock = new ReentrantLock();

    public User(String userId) {
        this.userId = userId;
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
    }

    public void removeSocket(SockJSSocket socket) {
        enforceLockHeld();
        final String sessionId = socket.webSession().id();
        final Set<SockJSSocket> socketSet = sessionIdToSocketMap.get(sessionId);
        if(socketSet != null) {
            socketSet.remove(socket);
        }
        sockets.remove(socket);
    }

    public Set<SockJSSocket> removeSession(String sessionId) {
        enforceLockHeld();
        final Set<SockJSSocket> socketSet = sessionIdToSocketMap.get(sessionId);
        sockets.removeAll(socketSet);
        return socketSet;
    }

    private void enforceLockHeld() {
        if(!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("User lock needs to be held");
        }
    }

    public boolean isLockedByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }
}
