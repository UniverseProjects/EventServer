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

public class SockJSSocketHandler implements Handler<SockJSSocket> {

    public static final String AUTH_BEARER = "Bearer ";
    public static final String TOKEN_ANONYMOUS = "anonymous";
    protected final EventServerVerticle verticle;

    public SockJSSocketHandler(EventServerVerticle verticle) {
        this.verticle = verticle;
    }

    @Override
    public void handle(SockJSSocket socket) {
        final Session session = socket.webSession();
        final User user = verticle.sharedDataService.getSessionToUserMap().get(session.id());
        final String authHeader = socket.headers().get(HttpHeaders.AUTHORIZATION);
        final String token;
        if(authHeader != null && authHeader.startsWith(AUTH_BEARER)) {
            token = authHeader.substring(AUTH_BEARER.length());
        } else {
            token = TOKEN_ANONYMOUS;
        }
        verticle.authService.authenticate(token, (authResponse) -> {
            if(authResponse.success) {
                onAuthSuccess(socket, user, authResponse);
            } else {
                onAuthError(socket, "Authentication failed");
            }
        }, (exception) -> {
            exception.printStackTrace();
            onAuthError(socket, "Error while authenticating");
        });
    }

    private void onAuthSuccess(final SockJSSocket socket, final User sessionUser, final AuthResponse authResponse) {
        User user = sessionUser;
        if(user == null) {
            user = new User(authResponse.userId);
            setupUser(user);
        }
        setupSocket(socket, user);
        updateChannels(user, authResponse.channels);
        sendOldMessages(socket, user);
    }

    private void setupUser(User user) {
        final String address = verticle.generateUserUpdateAddress(user);
        verticle.eventBus.<JsonArray>consumer(address, (message) -> {
            Set<String> channels = new LinkedHashSet<>();
            for(Object channelObj : message.body()) {
                channels.add((String) channelObj);
            }
            updateChannels(user, channels);
        });
    }

    private void updateChannels(User user, Set<String> channels) {
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
        });
    }

    private MessageConsumer<ChatMessage> registerChannel(String channel, User user) {
        final String address = verticle.generateChannelAddress(channel);
        return verticle.eventBus.consumer(address, (message) -> {
            final ChatEnvelope envelope = ChatEnvelope.forMessage(message.body());
            final JsonObject messageJson = envelope.toJson();
            final Buffer buffer = Buffer.buffer();
            messageJson.writeToBuffer(buffer);
            final List<String> socketWriterIds = verticle.sharedDataService.getSocketWriterIdsForUser(user);
            for(String writerId : socketWriterIds) {
                verticle.eventBus.send(writerId, buffer);
            }
        });
    }

    private void sendOldMessages(SockJSSocket socket, User user) {
        verticle.sharedDataService.getMessageMap((res) -> {
            if(res.succeeded()) {
                final AsyncMap<String, JsonArray> map = res.result();
                final List<String> channelNames = new ArrayList<>();
                user.getChannelConsumers((consumers) ->
                        channelNames.addAll(consumers.keySet())
                );
                for(String channel : channelNames) {
                    map.get(channel, (mapResult) -> {
                        if(mapResult.succeeded()) {
                            final JsonArray jsonArray = mapResult.result();
                            final List<ChatMessage> messages = new ArrayList<>();
                            jsonArray.forEach((messageObj) -> {
                                ChatMessage message = ChatMessageCodec.INSTANCE.fromJson((JsonObject) messageObj);
                                messages.add(message);
                            });
                            ChatEnvelope envelope = ChatEnvelope.forMessages(messages);
                            send(socket, envelope);
                        }
                    });
                }
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

    private void setupSocket(SockJSSocket socket, User user) {
        verticle.sharedDataService.getSessionToUserMap().put(socket.webSession().id(), user);
        final LocalMap<String, JsonArray> socketMap = verticle.sharedDataService.getSocketMap();
        JsonArray userSockets = socketMap.get(user.userId);
        if(userSockets == null) {
            userSockets = new JsonArray();
        }
        userSockets.add(socket.writeHandlerID());
        socketMap.put(user.userId, userSockets);

        socket.webSession().put("user", user);

        socket.exceptionHandler((throwable) ->
                System.err.println("Socket error for user "+user.userId)
        );
        socket.endHandler((a) -> onDisconnect(user));
    }

    private void onDisconnect(User user) {
        user.getChannelConsumers((map) -> {
            map.values().forEach(MessageConsumer<ChatMessage>::unregister);
            map.clear();
        });
    }
}
