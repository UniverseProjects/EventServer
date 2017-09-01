package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SockJSSocketHandler implements Handler<SockJSSocket> {

    private final Logger log = Logger.getLogger(getClass().getCanonicalName());

    public static final String AUTH_BEARER = "Bearer ";
    public static final String TOKEN_ANONYMOUS = "anonymous";
    public static final String SOCKET_MESSAGE_UPDATE = "update";
    protected final EventServerVerticle verticle;

    public SockJSSocketHandler(EventServerVerticle verticle) {
        this.verticle = verticle;
    }

    @Override
    public void handle(SockJSSocket socket) {
        final Session session = socket.webSession();
        final User user = verticle.sharedDataService.getSessionToUserMap().get(session.id());
        String uri = socket.uri();
        final String authHeader = socket.headers().get(HttpHeaders.AUTHORIZATION);
        final String token;
        if (authHeader != null && authHeader.startsWith(AUTH_BEARER)) {
            token = authHeader.substring(AUTH_BEARER.length());
        } else {
            token = TOKEN_ANONYMOUS;
        }
        final Handler<User> onAuthSuccess = (newUser) -> setupSocket(socket, newUser, token);
        if(verticle.serverMode == EventServerVerticle.ServerMode.TEST_CLIENT) {
            AuthResponse authResponse = new AuthResponse(true, "test");
            authResponse.channels.add("public");
            authResponse.channels.add("group.test");
            onAuthSuccess(socket, user, authResponse, onAuthSuccess);
        } else {
            executeAuthentication(socket, user, token, onAuthSuccess);
        }
    }

    private void executeAuthentication(SockJSSocket socket, User user, String token, Handler<User> onSuccess) {
        verticle.authService.authenticate(token, (authResponse) -> {
            if (authResponse.success) {
                onAuthSuccess(socket, user, authResponse, onSuccess);
            } else {
                onAuthError(socket, "Authentication failed");
            }
        }, (exception) -> {
            log.log(Level.SEVERE, "Error while authenticating", exception);
            onAuthError(socket, "Error while authenticating");
        });
    }

    private void onAuthSuccess(final SockJSSocket socket, final User sessionUser, final AuthResponse authResponse, Handler<User> onSuccess) {
        final User user;
        if (sessionUser == null) {
            user = new User(authResponse.userId);
            setupUser(user);
        } else {
            user = sessionUser;
        }
        updateChannels(user, authResponse.channels, (added) ->
                        findOldMessages(user, added, (channel, messages) -> {
                            ChatEnvelope envelope = ChatEnvelope.forMessages(messages);
                            send(socket, envelope);
                        })
        );
        if(onSuccess != null) {
            onSuccess.handle(user);
        }
    }

    private void setupUser(final User user) {
        final String updateUserAddress = verticle.generateUserUpdateAddress(user);
        verticle.eventBus.<JsonArray>consumer(updateUserAddress, (message) -> {
            Set<String> channels = new LinkedHashSet<>();
            for (Object channelObj : message.body()) {
                channels.add((String) channelObj);
            }
            updateChannels(user, channels, (added) ->
                    findOldMessages(user, added, (channel, messages) -> {
                        ChatEnvelope envelope = ChatEnvelope.forMessages(messages);
                        verticle.sharedDataService.getLocalSocketWriterIdsForUser(user, (writerIds) -> {
                            for(String id : writerIds) {
                                verticle.eventBus.publish(id, envelope);
                            }
                        });
                    }));
        });
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
                final MessageConsumer<ChatMessage> consumer = map.remove(channel);
                consumer.unregister();
                map.remove(channel);
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
        final String address = verticle.generateChannelAddress(channel);
        return verticle.eventBus.consumer(address, (message) -> {
            final ChatEnvelope envelope = ChatEnvelope.forMessage(message.body());
            final JsonObject messageJson = envelope.toJson();
            final Buffer buffer = Buffer.buffer();
            messageJson.writeToBuffer(buffer);
            verticle.sharedDataService.getLocalSocketWriterIdsForUser(user, (writerIds) -> {
                for (String writerId : writerIds) {
                    verticle.eventBus.send(writerId, buffer);
                }
            });
        });
    }

    private void findOldMessages(User user, Set<String> newChannelNames, BiConsumer<String, List<ChatMessage>> messageHandler) {
        verticle.sharedDataService.getMessageMap((mapResult) -> {
            if (mapResult.succeeded()) {
                final AsyncMap<String, JsonArray> map = mapResult.result();
                final Set<String> channelNames;
                if (newChannelNames == null) {
                    channelNames = new LinkedHashSet<>();
                    user.getChannelConsumers((consumers) ->
                                    channelNames.addAll(consumers.keySet())
                    );
                } else {
                    channelNames = newChannelNames;
                }
                for (String channel : channelNames) {
                    map.get(channel, (result) -> {
                        if (result.succeeded()) {
                            final JsonArray jsonArray = result.result();
                            final List<ChatMessage> messages = new ArrayList<>();
                            jsonArray.forEach((messageObj) -> {
                                ChatMessage message = ChatMessageCodec.INSTANCE.fromJson((JsonObject) messageObj);
                                messages.add(message);
                            });
                            messageHandler.accept(channel, messages);
                        }
                    });
                }
            } else {
                log.log(Level.WARNING, "Error getting message-map", mapResult.cause());
            }
        });
    }

    private void send(SockJSSocket socket, ChatEnvelope envelope) {
        Buffer buffer = Buffer.buffer();
        JsonObject json = envelope.toJson();
        json.writeToBuffer(buffer);
        socket.write(buffer);
    }

    private void onAuthError(SockJSSocket socket, String message) {
        Buffer buffer = Buffer.buffer();
        ChatEnvelope envelope = ChatEnvelope.forError(message);
        envelope.toJson().writeToBuffer(buffer);
        socket.write(buffer);
        socket.close();
    }

    private void setupSocket(SockJSSocket socket, User user, String token) {
        verticle.sharedDataService.getSessionToUserMap().put(socket.webSession().id(), user);
        verticle.sharedDataService.getGlobalSocketMap((mapResult) -> {
            if (mapResult.succeeded()) {
                final AsyncMap<String, JsonArray> asyncMap = mapResult.result();
                asyncMap.get(user.userId, (result) -> {
                    Set<String> set = new LinkedHashSet<>();
                    if (result.succeeded()) {
                        @SuppressWarnings("unchecked")
                        List<String> list = result.result().getList();
                        set.addAll(list);
                    }
                    set.add(socket.writeHandlerID());
                    JsonArray newValue = new JsonArray(new ArrayList<>(set));
                    asyncMap.put(user.userId, newValue, null);
                });
            } else {
                log.log(Level.WARNING, "Error getting global socket-map", mapResult.cause());
            }
        });
        final LocalMap<String, JsonArray> localSocketMap = verticle.sharedDataService.getLocalSocketMap();
        JsonArray socketWritersJson = localSocketMap.get(user.userId);
        if (socketWritersJson == null) {
            socketWritersJson = new JsonArray();
        }
        socketWritersJson.add(socket.writeHandlerID());
        localSocketMap.put(user.userId, socketWritersJson);
        socket.handler((buffer) -> {
            if (buffer.length() == SOCKET_MESSAGE_UPDATE.length() && SOCKET_MESSAGE_UPDATE.equals(buffer.toString())) {
                executeAuthentication(socket, user, token, null);
            } else {
                Buffer loggedBuffer = buffer;
                if (loggedBuffer.length() > 100) {
                    loggedBuffer = loggedBuffer.slice(0, 100);
                }
                log.severe("Received bad message on socket for user " + user.userId + ": " + loggedBuffer.toString());
            }
        });
        socket.exceptionHandler((throwable) ->
                        log.severe("Socket error for user " + user.userId)
        );
        socket.endHandler((ignored) -> onDisconnect(socket, user));
    }

    private void onDisconnect(SockJSSocket socket, User user) {
        user.getChannelConsumers((map) -> {
            map.values().forEach(MessageConsumer<ChatMessage>::unregister);
            map.clear();
        });
        final LocalMap<String, JsonArray> localSocketMap = verticle.sharedDataService.getLocalSocketMap();
        final JsonArray localWriters = localSocketMap.get(user.userId);
        localWriters.remove(socket.writeHandlerID());
        localSocketMap.put(user.userId, localWriters);
        verticle.sharedDataService.getGlobalSocketMap((mapResult) -> {
            if(mapResult.succeeded()) {
                final AsyncMap<String, JsonArray> asyncMap = mapResult.result();
                asyncMap.get(user.userId, (result) -> {
                    if (result.succeeded()) {
                        final JsonArray writers = result.result();
                        writers.remove(socket.writeHandlerID());
                        asyncMap.put(user.userId, writers, null);
                    }
                });
            } else {
                log.log(Level.WARNING, "Error getting global socket-map", mapResult.cause());
            }
        });

    }
}
