package com.universeprojects.eventserver;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

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
    public void handle(final SockJSSocket socket) {
        verticle.getVertx().executeBlocking((future) -> {
            try {
                handleConnection(socket);
                future.complete();
            } catch (Exception ex) {
                log.error("Error processing socket connection", ex);
                future.fail(ex);
            }
        }, false, (future) -> {});
    }

    public void handleConnection(final SockJSSocket socket) {
        final Session session = socket.webSession();
        final User sessionUser = verticle.sessionService.getUserForSession(session.id());
        final String uri = socket.uri();
        final QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri);
        final Map<String, List<String>> params = queryStringDecoder.parameters();
        final String token = extractParam(params, PARAM_TOKEN, TOKEN_ANONYMOUS);
        final boolean fetchOldMessages = Boolean.valueOf(extractParam(params, PARAM_FETCH_OLD_MESSAGES, Boolean.TRUE.toString()));

        verticle.logConnectionEvent(() -> "Established connection on " + socket.localAddress() + " to client " + socket.remoteAddress());

        final BiConsumer<User, Set<String>> onSuccess = (newUser, channels) ->
            processNewUser(socket, token, fetchOldMessages, newUser, channels);

        if (verticle.serverMode == EventServerVerticle.ServerMode.TEST_CLIENT) {
            AuthResponse authResponse = new AuthResponse(true, token);
            authResponse.channels.add("public");
            authResponse.channels.add("group.test");
            onAuthSuccess(sessionUser, authResponse, onSuccess);
        } else {
            executeAuthentication(socket, sessionUser, token, onSuccess);
        }
    }

    private void processNewUser(SockJSSocket socket, String token, boolean fetchOldMessages, User newUser, Set<String> channels) {
        setupSocket(socket, newUser, token);
        verticle.channelService.updateSubscriptions(newUser, channels);
        if (fetchOldMessages) {
            verticle.fetchHistoryMessages(channels, (channel, messages) -> {
                verticle.logConnectionEvent(() -> "Sending old messages for channel " + channel + " to user " + newUser);
                ChatEnvelope envelope = ChatEnvelope.forMessages(messages);
                send(socket, envelope);
            });
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
        verticle.logConnectionEvent(() -> "Authenticating connection " + socket.remoteAddress());
        verticle.authService.authenticate(token, (authResponse) -> {
            verticle.logConnectionEvent(() -> "Authentication for connection " + socket.remoteAddress() + ": " + authResponse.success);
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
        final User mapUser = verticle.userService.getOrCreateUser(authResponse.userId);
        if (sessionUser == null) {
            user = mapUser;
            verticle.logConnectionEvent(() -> "User connected again with a new session: " + user);
        } else if (sessionUser != mapUser) {
            Set<SockJSSocket> sessionUserSockets = sessionUser.executeLockedReturning(su -> su.removeSession(sessionUser.userId));
            user = mapUser;
            sessionUserSockets.forEach(user::removeSocket);
            verticle.logConnectionEvent(() -> "User identity changed from " + sessionUser + " to " + user);
        } else {
            user = sessionUser;
            verticle.logConnectionEvent(() -> "User connected again: " + user);
        }
        int newCount = user.sockets.size() + 1;
        verticle.logConnectionEvent(() -> "User " + user + " connected. " + newCount + " connections active");
        if (onSuccess != null) {
            onSuccess.accept(user, authResponse.channels);
        }
    }

    private void send(SockJSSocket socket, ChatEnvelope envelope) {
        JsonObject json = envelope.toJson();
        Buffer buffer = json.toBuffer();
        socket.write(buffer);
    }

    private void onAuthError(SockJSSocket socket, String message) {
        ChatEnvelope envelope = ChatEnvelope.forError(message);
        send(socket, envelope);
        socket.close();
    }

    private void setupSocket(SockJSSocket socket, User user, String token) {
        user.executeLocked(u -> u.registerSocket(socket));
        socket.handler((buffer) -> onSocketMessage(socket, user, token, buffer));
        socket.exceptionHandler((throwable) ->
                log.error("Socket error for user " + user)
        );
        socket.endHandler((ignored) -> onDisconnect(socket, user));
        verticle.sessionService.putSession(socket, user);
    }

    private void onDisconnect(SockJSSocket socket, User user) {
        user.executeLocked((u) -> {
            u.removeSocket(socket);
            verticle.sessionService.removeSession(socket.webSession().id());
            if (u.sockets.isEmpty()) {
                verticle.userService.checkAndRemoveUser(u, u.sockets::isEmpty);
            }
        });
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
        final BiConsumer<User, Set<String>> onAuthSuccess = (newUser, channels) -> {
            final Set<String> added = verticle.channelService.updateSubscriptions(user, channels);
            verticle.fetchHistoryMessages(added, (channel, messages) -> {
                ChatEnvelope envelope = ChatEnvelope.forMessages(messages);
                send(socket, envelope);
            });
        };
        executeAuthentication(socket, user, token, onAuthSuccess);

    }
}
