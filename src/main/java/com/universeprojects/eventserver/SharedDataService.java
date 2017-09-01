package com.universeprojects.eventserver;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.SharedData;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SharedDataService {
    private final Logger log = Logger.getLogger(getClass().getCanonicalName());
    private final SharedData sd;

    public SharedDataService(SharedData sd) {
        this.sd = sd;
    }

    public LocalMap<String, User> getSessionToUserMap() {
        return sd.<String, User>getLocalMap("sessionUsers");
    }

    public void getGlobalSocketMap(Handler<AsyncResult<AsyncMap<String, JsonArray>>> resultHandler) {
        sd.getClusterWideMap("sockets.global", resultHandler);
    }

    public LocalMap<String, JsonArray> getLocalSocketMap() {
        return sd.getLocalMap("sockets.local");
    }

    public void getMessageMap(Handler<AsyncResult<AsyncMap<String, JsonArray>>> resultHandler) {
        sd.getClusterWideMap("messages", resultHandler);
    }

    public void getLocalSocketWriterIdsForUser(User user, Handler<List<String>> resultHandler) {
        final JsonArray json = getLocalSocketMap().get(user.userId);
        if(json != null) {
            //noinspection unchecked
            resultHandler.handle(json.getList());
        }
    }

    public void getGlobalSocketWriterIdsForUser(String userId, Handler<List<String>> resultHandler) {
        getGlobalSocketMap((mapResult) -> {
            if (mapResult.succeeded()) {
                mapResult.result().get(userId, (result) -> {
                    if (result.succeeded() && result.result() != null) {
                        JsonArray json = result.result();
                        //noinspection unchecked
                        List<String> list = json.getList();
                        resultHandler.handle(list);
                    }
                });
            } else {
                log.log(Level.WARNING, "Error getting message-map", mapResult.cause());
            }
        });
    }
}
