package com.universeprojects.eventserver;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionService {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Map<String, User> sessionIdToUserMap = new ConcurrentHashMap<>();

    public User getUserForSession(String sessionId) {
        final User user = sessionIdToUserMap.get(sessionId);
        if(user != null && user.isRemoved()) {
            log.warn("Found removed user "+user.userId+" for session "+sessionId);
            sessionIdToUserMap.remove(sessionId);
            return null;
        }
        return user;
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
