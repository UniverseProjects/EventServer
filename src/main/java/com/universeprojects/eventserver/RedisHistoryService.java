package com.universeprojects.eventserver;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

@SuppressWarnings("FieldCanBeLocal")
public class RedisHistoryService implements HistoryService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String CONFIG_REDIS_HOST = "redis_host";
    public static final String CONFIG_REDIS_PORT = "redis_port";
    public static final String CONFIG_HISTORY_EXPIRE = "redis_history_expire";


    private final RedisClient redisClient;
    private final RedisChatCodec redisChatCodec;
    private final long historyExpireSeconds;
    private final StatefulRedisConnection<String, ChatMessage> connection;

    public RedisHistoryService() {
        final String hostname = Config.getString(CONFIG_REDIS_HOST, "redis");
        final int port = Config.getInt(CONFIG_REDIS_PORT, 6379);
        final Duration duration = Duration.ofSeconds(10);
        this.redisClient = RedisClient.create(new RedisURI(hostname, port, duration));
        this.redisChatCodec = new RedisChatCodec();
        this.historyExpireSeconds = Config.getLong(CONFIG_HISTORY_EXPIRE, 24 * 60 * 60);
        this.connection = this.redisClient.connect(redisChatCodec);
    }

    @Override
    public void storeChatHistory(String channel, int historySize, List<ChatMessage> messages) {
        if (messages.isEmpty()) return;
        ChatMessage[] messageArray = messages.toArray(new ChatMessage[messages.size()]);
        final String key = generateChannelKey(channel);
        final RedisAsyncCommands<String, ChatMessage> commands = connection.async();
        BiConsumer<Object, Throwable> errorHandler = (result, ex) -> {
            if(ex != null) {
                log.error("Error storing history entries", ex);
            }
        };

        commands.multi().whenComplete(errorHandler);
        commands.lpush(key, messageArray).whenComplete(errorHandler);
        commands.ltrim(key, 0, historySize).whenComplete(errorHandler);
        if(isVolatileChannel(channel)) {
            commands.expire(key, historyExpireSeconds);
        }
        commands.exec().whenComplete(errorHandler);
    }

    private boolean isVolatileChannel(String channel) {
        return channel.startsWith("?");
    }

    @Override
    public void fetchHistoryMessages(Set<String> channels, int historySize, BiConsumer<String, List<ChatMessage>> messageHandler) {
        final RedisAsyncCommands<String, ChatMessage> commands = connection.async();
        for (final String channel : channels) {
            final String key = generateChannelKey(channel);
            commands.lrange(key, 0, historySize - 1).whenComplete(
                (list, ex) -> {
                    if (ex != null) {
                        log.error("Error fetching history entries", ex);
                    } else {
                        Collections.reverse(list); //Redis returns a reversed list
                        list.forEach((message) -> {
                            if(message.additionalData == null) {
                                message.additionalData = new JsonObject();
                            }
                            message.additionalData.put("__history", true);
                        });
                        messageHandler.accept(channel, list);
                    }
                });
        }
    }

    private String generateChannelKey(String channel) {
        return "channel:" + channel;
    }
}
