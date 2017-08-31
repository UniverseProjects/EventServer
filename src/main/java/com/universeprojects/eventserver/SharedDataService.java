package com.universeprojects.eventserver;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SharedDataService {
    private final SharedData sd;

    public SharedDataService(SharedData sd) {
        this.sd = sd;
    }

    public LocalMap<String, User> getSessionToUserMap() {
        return sd.<String, User>getLocalMap("sessionUsers");
    }

    public LocalMap<String, JsonArray> getSocketMap() {
        return sd.<String, JsonArray>getLocalMap("sockets");
    }

    public void getMessageMap(Handler<AsyncResult<AsyncMap<String, JsonArray>>> resultHandler) {
        sd.getClusterWideMap("messages", resultHandler);
    }

    public List<String> getSocketWriterIdsForUser(User user) {
        JsonArray json = getSocketMap().get(user.userId);
        if(json == null) return Collections.emptyList();
        //noinspection unchecked
        return json.getList();
    }
}
