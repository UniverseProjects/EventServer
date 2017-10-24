package com.universeprojects.eventserver;

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
}
