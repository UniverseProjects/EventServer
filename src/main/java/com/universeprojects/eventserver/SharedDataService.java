package com.universeprojects.eventserver;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.Lock;
import io.vertx.core.shareddata.SharedData;

public class SharedDataService {
    private final SharedData sd;

    public SharedDataService(SharedData sd) {
        this.sd = sd;
    }

    public void getSlackLock(Handler<AsyncResult<Lock>> handler) {
        sd.getLockWithTimeout("slack", 10 * 1000, handler);
    }

    public void getChannelLock(String key, Handler<AsyncResult<Lock>> handler) {
        sd.getLockWithTimeout("messages:"+key, 1000, handler);
    }

    public void getMessageMap(Handler<AsyncResult<AsyncMap<String, JsonArray>>> resultHandler) {
        sd.getClusterWideMap("messages", resultHandler);
    }

}
