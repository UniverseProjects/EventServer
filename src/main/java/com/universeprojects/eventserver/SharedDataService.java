package com.universeprojects.eventserver;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.Lock;
import io.vertx.core.shareddata.SharedData;

import java.util.List;

public class SharedDataService {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final SharedData sd;

    public SharedDataService(SharedData sd) {
        this.sd = sd;
    }

    public void getSlackLock(Handler<AsyncResult<Lock>> handler) {
        sd.getLockWithTimeout("slack", 10 * 1000, handler);
    }

    public LocalMap<String, User> getSessionToUserMap() {
        return sd.<String, User>getLocalMap("sessionUsers");
    }

    public LocalMap<String, User> getUserIdToUserMap() {
        return sd.<String, User>getLocalMap("users");
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
                log.warn("Error getting message-map", mapResult.cause());
            }
        });
    }
}
