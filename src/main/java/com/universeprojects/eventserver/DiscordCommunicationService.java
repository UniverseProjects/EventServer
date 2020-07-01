package com.universeprojects.eventserver;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.MessageUpdateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;
import io.vertx.core.json.JsonArray;

import java.util.Optional;
import java.util.function.Consumer;

public class DiscordCommunicationService extends CommunicationService {

    private static final String SERVICE_NAME = "Discord";
    private static final String CONFIG_DISCORD_TOKEN = "discord.token";

    private final String token;
    private GatewayDiscordClient gateway;

    public DiscordCommunicationService(EventServerVerticle verticle) {
        super(verticle, SERVICE_NAME);
        token = Config.getString(CONFIG_DISCORD_TOKEN, null);
    }

    boolean localCanActivateOutgoing() {
        return localCanActivateIncoming();
    }

    boolean localCanActivateIncoming() {
        return token != null && token.length() > 0;
    }

    void activateIncoming() {
        DiscordClient client = DiscordClient.create(token);
        gateway = client.login().block();

        final Consumer<MessageCreateEvent> handleMessage = event -> {
            final Message message = event.getMessage();
            final MessageChannel channel = message.getChannel().block();
            final String discordChannel = Long.toString(channel.getId().asLong());
            final String discordChannelName = channel.getMention();
            final Optional<User> author = message.getAuthor();
            final boolean isBot = author.map(user -> user.isBot()).orElse(false);
            if (incomingChannelMap.containsKey(discordChannel) && !isBot) {
                final String insideChannel = incomingChannelMap.get(discordChannel);
                final String username = author.map(user -> user.getUsername()).orElse("unknown");
                sendInsideMessage(insideChannel, discordChannel, username, message.getContent(), message.getTimestamp().getEpochSecond());
            }
        };

        final Consumer<MessageUpdateEvent> updateMessage = event -> {
            verticle.logConnectionEvent(() -> "Received an edited message from discord channel " + Long.toString(event.getChannelId().asLong()));
        };

        gateway.on(MessageCreateEvent.class).subscribe(handleMessage);
        gateway.on(MessageUpdateEvent.class).subscribe(updateMessage);
    }

    void sendOutsideMessage(String sourceChannel, String remoteChannel, String text, String fallbackText, String author, String authorLink, String authorColor, JsonArray additionalFields) {
        final MessageChannel channel = (MessageChannel) gateway.getChannelById(Snowflake.of(Long.parseLong(remoteChannel))).block();
        channel.createMessage(author + ": " + text).block();
    }

    @Override
    public String toString() {
        return "DiscordCommunicationService{" +
                "active= " + isActive() +
                ", outgoingChannelMap=" + outgoingChannelMap +
                ", incomingChannelMap=" + incomingChannelMap +
                '}';
    }
}
