package com.universeprojects.eventserver;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

public class AuthResponse {
    boolean success;
    String userId;
    final Set<String> channels = new LinkedHashSet<>();

    public static AuthResponse fromJson(JsonObject json) {
        AuthResponse response = new AuthResponse();
        response.success = json.getBoolean("success", false);
        response.userId = json.getString("userId");
        JsonArray channels = json.getJsonArray("channels");
        if(channels != null) {
            channels.forEach(channelObj -> response.channels.add((String)channelObj));
        }
        return response;
    }
}
