package com.universeprojects.eventserver;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public class RedisService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String CONFIG_REDIS_HOST = "redis.host";
    public static final String CONFIG_REDIS_PORT = "redis.port";

    private final RedisClient redisClient;
    private final RedisChatCodec redisChatCodec;

    public RedisService() {
        final String hostname = Config.getString(CONFIG_REDIS_HOST, "redis");
        final int port = Config.getInt(CONFIG_REDIS_PORT, 6379);
        final Duration duration = Duration.ofSeconds(10);
        this.redisClient = RedisClient.create(new RedisURI(hostname, port, duration));
        this.redisChatCodec = new RedisChatCodec();
    }

    public void storeChatHistory(String channel, int historySize, List<ChatMessage> messages) {
        if (messages.isEmpty()) return;
        ChatMessage[] messageArray = messages.toArray(new ChatMessage[messages.size()]);
        final String key = generateChannelKey(channel);
        final RedisAsyncCommands<String, ChatMessage> commands =
            this.redisClient.connect(redisChatCodec).async();
        BiConsumer<Object, Throwable> errorHandler = (result, ex) -> {
            if(ex != null) {
                log.error("Error storing history entries", ex);
            }
        };

        commands.multi().whenComplete(errorHandler);
        commands.lpush(key, messageArray).whenComplete(errorHandler);
        commands.ltrim(key, 0, historySize).whenComplete(errorHandler);
        commands.exec().whenComplete(errorHandler);
    }

    public void fetchHistoryMessages(Set<String> channels, int historySize, BiConsumer<String, List<ChatMessage>> messageHandler) {
        final RedisAsyncCommands<String, ChatMessage> commands =
            this.redisClient.connect(redisChatCodec).async();
        for (final String channel : channels) {
            final String key = generateChannelKey(channel);
            commands.lrange(key, 0, historySize - 1).whenComplete(
                (list, ex) -> {
                    if (ex != null) {
                        log.error("Error fetching history entries", ex);
                    } else {
                        Collections.reverse(list);
                        messageHandler.accept(channel, list);
                    }
                });
        }
    }

    private String generateChannelKey(String channel) {
        return "channel:" + channel;
    }
}
