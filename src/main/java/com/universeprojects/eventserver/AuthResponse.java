package com.universeprojects.eventserver;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

public class AuthResponse {
    public final boolean success;
    public final String userId;
    public final Set<String> channels = new LinkedHashSet<>();

    public AuthResponse(boolean success, String userId) {
        this.success = success;
        this.userId = userId;
    }

    public static AuthResponse fromJson(JsonObject json) {
        boolean success = json.getBoolean("success", false);
        String userId = json.getString("userId");
        AuthResponse response = new AuthResponse(success, userId);
        JsonArray channels = json.getJsonArray("channels");
        if(channels != null) {
            channels.forEach(channelObj -> response.channels.add((String)channelObj));
        }
        return response;
    }
}
