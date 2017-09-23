package com.universeprojects.eventserver;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public class SockJSSocketHandler implements Handler<SockJSSocket> {

    public static final String PARAM_TOKEN = "token";
    public static final String PARAM_FETCH_OLD_MESSAGES = "fetchOldMessages";
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String TOKEN_ANONYMOUS = "anonymous";
    public static final String SOCKET_MESSAGE_UPDATE = "update";
    private final EventServerVerticle verticle;

    public SockJSSocketHandler(EventServerVerticle verticle) {
        this.verticle = verticle;

    }

    @Override
    public void handle(SockJSSocket socket) {
        final Session session = socket.webSession();
        final User user = verticle.sharedDataService.getSessionToUserMap().get(session.id());
        String uri = socket.uri();
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri);
        Map<String, List<String>> params = queryStringDecoder.parameters();
        final String token = extractParam(params, PARAM_TOKEN, TOKEN_ANONYMOUS);
        final boolean fetchOldMessages = Boolean.valueOf(extractParam(params, PARAM_FETCH_OLD_MESSAGES, Boolean.TRUE.toString()));


        verticle.logConnectionEvent(() -> "Established connection on " + socket.localAddress() + " to client " + socket.remoteAddress());

        final BiConsumer<User, Set<String>> onSuccess = (newUser, channels) ->
                updateChannels(newUser, channels, (added) -> {
                    if(fetchOldMessages) {
                        findOldMessages(channels, (channel, messages) -> {
                            verticle.logConnectionEvent(() -> "Sending old messages for channel " + channel + " to user " + user);
                            ChatEnvelope envelope = ChatEnvelope.forMessages(messages);
                            send(socket, envelope);
                        });
                    }
                    setupSocket(socket, newUser, token);
                });

        if (verticle.serverMode == EventServerVerticle.ServerMode.TEST_CLIENT) {
            AuthResponse authResponse = new AuthResponse(true, "test");
            authResponse.channels.add("public");
            authResponse.channels.add("group.test");
            onAuthSuccess(user, authResponse, onSuccess);
        } else {
            executeAuthentication(socket, user, token, onSuccess);
        }
    }

    private String extractParam(Map<String, List<String>> params, String key, String defaultValue) {
        List<String> values = params.get(key);
        final String token;
        if (values != null && values.size() == 1 && !values.get(0).isEmpty()) {
            token = values.get(0);
        } else {
            token = defaultValue;
        }
        return token;
    }

    private void executeAuthentication(SockJSSocket socket, User user, String token, BiConsumer<User, Set<String>> onSuccess) {
        verticle.logConnectionEvent(() -> "Authenticating connection "+socket.remoteAddress());
        verticle.authService.authenticate(token, (authResponse) -> {
            verticle.logConnectionEvent(() -> "Authentication for connection "+socket.remoteAddress()+": "+authResponse.success);
            if (authResponse.success) {
                onAuthSuccess(user, authResponse, onSuccess);
            } else {
                onAuthError(socket, "Authentication failed");
            }
        }, (exception) -> {
            log.error("Error while authenticating", exception);
            onAuthError(socket, "Error while authenticating");
        });
    }

    private void onAuthSuccess(final User sessionUser, final AuthResponse authResponse, BiConsumer<User, Set<String>> onSuccess) {
        final User user;
        if (sessionUser == null) {
            user = new User(authResponse.userId);
            setupUser(user);
            verticle.logConnectionEvent(() -> "New user connected: "+user);
        } else {
            user = sessionUser;
            verticle.logConnectionEvent(() -> "User connected again: "+user);
        }
        user.connectionCounter.incrementAndGet();
        if (onSuccess != null) {
            onSuccess.accept(user, authResponse.channels);
        }
    }

    private void setupUser(final User user) {
        final String updateUserAddress = verticle.generateUserUpdateAddress(user);
        verticle.eventBus.<JsonArray>consumer(updateUserAddress, (message) -> updateChannelsFromEventBus(user, message));
    }

    private void updateChannels(User user, Set<String> channels, Handler<Set<String>> addedChannelsHandler) {
        user.getChannelConsumers((map) -> {
            Set<String> addedChannels = new LinkedHashSet<>();
            addedChannels.addAll(channels);
            addedChannels.removeAll(map.keySet());

            Set<String> removedChannels = new LinkedHashSet<>();
            removedChannels.addAll(map.keySet());
            removedChannels.removeAll(channels);


            for (String channel : removedChannels) {
                final MessageConsumer<ChatMessage> consumer = map.get(channel);
                consumer.unregister((result) -> {
                    if (result.succeeded()) {
                        user.getChannelConsumers(mapForRemoval -> mapForRemoval.remove(channel));
                    } else {
                        log.error("Error unregistering channel handler on channel " + channel + " for user " + user, result.cause());
                    }
                });
            }

            for (String channel : addedChannels) {
                final MessageConsumer<ChatMessage> consumer = registerChannel(channel, user);
                map.put(channel, consumer);
            }
            if (addedChannelsHandler != null) {
                addedChannelsHandler.handle(addedChannels);
            }
        });
    }

    private MessageConsumer<ChatMessage> registerChannel(String channel, User user) {
        verticle.logConnectionEvent(() -> "Registering channel-consumer for channel "+channel+" to user " + user);
        final String address = verticle.generateChannelAddress(channel);
        return verticle.eventBus.consumer(address, (message) -> handleChannelMessage(user, message));
    }

    private void handleChannelMessage(User user, Message<ChatMessage> message) {
        final ChatMessage chatMessage = message.body();
        final ChatEnvelope envelope = ChatEnvelope.forMessage(chatMessage);
        verticle.logConnectionEvent(() -> "Sending channel-message for channel "+chatMessage.channel+" to user " + user + ": " + chatMessage.text);
        final JsonObject messageJson = envelope.toJson();
        final Buffer buffer = Buffer.buffer(messageJson.encode());
        verticle.sharedDataService.getLocalSocketWriterIdsForUser(user, (writerIds) -> {
            for (String writerId : writerIds) {
                verticle.eventBus.send(writerId, buffer);
            }
        });
    }

    private void findOldMessages(Set<String> channelNames, BiConsumer<String, List<ChatMessage>> messageHandler) {
        if(channelNames.isEmpty()) return;
        verticle.sharedDataService.getMessageMap((mapResult) -> {
            if (mapResult.succeeded()) {
                final AsyncMap<String, JsonArray> map = mapResult.result();
                for (String channel : channelNames) {
                    map.get(channel, (result) -> {
                        if (result.succeeded() && result.result() != null) {
                            final JsonArray jsonArray = result.result();
                            if (jsonArray != null && !jsonArray.isEmpty()) {
                                final List<ChatMessage> messages = new ArrayList<>();
                                jsonArray.forEach((messageObj) -> {
                                    ChatMessage message = ChatMessageCodec.INSTANCE.fromJson((JsonObject) messageObj);
                                    messages.add(message);
                                });
                                messageHandler.accept(channel, messages);
                            }
                        }
                    });
                }
            } else {
                log.warn("Error getting message-map", mapResult.cause());
            }
        });
    }

    private void send(SockJSSocket socket, ChatEnvelope envelope) {
        JsonObject json = envelope.toJson();
        Buffer buffer = Buffer.buffer(json.encode());
        socket.write(buffer);
    }

    private void onAuthError(SockJSSocket socket, String message) {
        ChatEnvelope envelope = ChatEnvelope.forError(message);
        Buffer buffer = Buffer.buffer(envelope.toJson().encode());
        socket.write(buffer);
        socket.close();
    }

    private void setupSocket(SockJSSocket socket, User user, String token) {
        verticle.sharedDataService.getSessionToUserMap().put(socket.webSession().id(), user);
        verticle.sharedDataService.getGlobalSocketMap((mapResult) -> {
            if (mapResult.succeeded()) {
                verticle.logConnectionEvent(() -> "Registering socket "+socket.writeHandlerID()+" for user " + user);
                final AsyncMap<String, JsonArray> asyncMap = mapResult.result();
                asyncMap.get(user.userId, (result) -> {
                    Set<String> set = new LinkedHashSet<>();
                    if (result.succeeded() && result.result() != null) {
                        @SuppressWarnings("unchecked")
                        List<String> list = result.result().getList();
                        set.addAll(list);
                    }
                    set.add(socket.writeHandlerID());
                    JsonArray newValue = new JsonArray(new ArrayList<>(set));
                    asyncMap.put(user.userId, newValue, null);
                });
            } else {
                log.warn("Error getting global socket-map", mapResult.cause());
            }
        });
        final LocalMap<String, JsonArray> localSocketMap = verticle.sharedDataService.getLocalSocketMap();
        JsonArray socketWritersJson = localSocketMap.get(user.userId);
        if (socketWritersJson == null) {
            socketWritersJson = new JsonArray();
        }
        socketWritersJson.add(socket.writeHandlerID());
        localSocketMap.put(user.userId, socketWritersJson);
        socket.handler((buffer) -> onSocketMessage(socket, user, token, buffer));
        socket.exceptionHandler((throwable) ->
            log.error("Socket error for user " + user)
        );
        socket.endHandler((ignored) -> onDisconnect(socket, user));
    }

    private void onSocketMessage(SockJSSocket socket, User user, String token, Buffer buffer) {
        if (buffer.length() == SOCKET_MESSAGE_UPDATE.length() && SOCKET_MESSAGE_UPDATE.equals(buffer.toString())) {
            updateChannelsForSocket(socket, user, token);
        } else {
            Buffer loggedBuffer = buffer;
            if (loggedBuffer.length() > 100) {
                loggedBuffer = loggedBuffer.slice(0, 100);
            }
            log.error("Received bad message on socket for user " + user + ": " + loggedBuffer.toString());
        }
    }

    private void updateChannelsForSocket(SockJSSocket socket, User user, String token) {
        final BiConsumer<User, Set<String>> onAuthSuccess = (newUser, channels) ->
                updateChannels(user, channels, (added) ->
                        findOldMessages(added, (channel, messages) -> {
                            ChatEnvelope envelope = ChatEnvelope.forMessages(messages);
                            send(socket, envelope);
                        }));
        executeAuthentication(socket, user, token, onAuthSuccess);
    }

    private void updateChannelsFromEventBus(User user, Message<JsonArray> message) {
        Set<String> channels = new LinkedHashSet<>();
        for (Object channelObj : message.body()) {
            channels.add((String) channelObj);
        }
        updateChannels(user, channels, (added) ->
                findOldMessages(added, (channel, messages) -> {
                    ChatEnvelope envelope = ChatEnvelope.forMessages(messages);
                    Buffer buffer  = Buffer.buffer(envelope.toJson().encode());
                    verticle.sharedDataService.getLocalSocketWriterIdsForUser(user, (writerIds) -> {
                        for (String id : writerIds) {
                            verticle.eventBus.publish(id, buffer);
                        }
                    });
                }));
    }

    private void onDisconnect(SockJSSocket socket, User user) {
        long newCount = user.connectionCounter.decrementAndGet();
        verticle.logConnectionEvent(() -> "User "+user+" disconnected. "+newCount+" connections left");
        if(newCount <= 0) {
            user.getChannelConsumers((map) -> {
                Map<String, MessageConsumer<ChatMessage>> consumers = new LinkedHashMap<>(map);
                for (Map.Entry<String, MessageConsumer<ChatMessage>> entry : consumers.entrySet()) {
                    verticle.logConnectionEvent(() -> "Unregistering channel handler on channel " + entry.getKey() + " for user " + user);
                    entry.getValue().unregister((result) -> {
                        if (result.succeeded()) {
                            user.getChannelConsumers(mapForRemoval -> mapForRemoval.remove(entry.getKey()));
                        } else {
                            log.error("Error unregistering channel handler on channel " + entry.getKey() + " for user " + user, result.cause());
                        }
                    });
                }
            });
        }
        final LocalMap<String, JsonArray> localSocketMap = verticle.sharedDataService.getLocalSocketMap();
        final JsonArray localWriters = localSocketMap.get(user.userId);
        localWriters.remove(socket.writeHandlerID());
        localSocketMap.put(user.userId, localWriters);
        verticle.sharedDataService.getGlobalSocketMap((mapResult) -> {
            if (mapResult.succeeded()) {
                final AsyncMap<String, JsonArray> asyncMap = mapResult.result();
                asyncMap.get(user.userId, (result) -> {
                    if (result.succeeded() && result.result() != null) {
                        final JsonArray writers = result.result();
                        writers.remove(socket.writeHandlerID());
                        asyncMap.put(user.userId, writers, null);
                    }
                });
            } else {
                log.warn("Error getting global socket-map", mapResult.cause());
            }
        });

    }
}
