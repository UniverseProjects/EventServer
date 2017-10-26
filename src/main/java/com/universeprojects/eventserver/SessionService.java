package com.universeprojects.eventserver;

import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionService {
    private final Map<String, User> sessionIdToUserMap = new ConcurrentHashMap<>();

    public User getUserForSession(String sessionId) {
        return sessionIdToUserMap.get(sessionId);
    }

    public void removeSession(String sessionId) {
        sessionIdToUserMap.remove(sessionId);
    }

    public void putSession(SockJSSocket socket, User user) {
        putSession(socket.webSession().id(), user);
    }

    public void putSession(String sessionId, User user) {
        sessionIdToUserMap.put(sessionId, user);
    }
}
