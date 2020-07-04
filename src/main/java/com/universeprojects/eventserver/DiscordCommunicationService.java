package com.universeprojects.eventserver;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.MessageUpdateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.GuildChannel;
import discord4j.core.object.entity.channel.MessageChannel;
import io.vertx.core.json.JsonArray;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordCommunicationService extends CommunicationService {

    private static final String SERVICE_NAME = "Discord";
    private static final String CONFIG_DISCORD_TOKEN = "discord.token";

    private final String token;
    private GatewayDiscordClient gateway;

    DiscordCommunicationService(EventServerVerticle verticle) {
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
            final Optional<User> author = message.getAuthor();
            final boolean isBot = author.map(User::isBot).orElse(false);
            if (incomingChannelMap.containsKey(discordChannel) && !isBot) {
                final String insideChannel = incomingChannelMap.get(discordChannel);
                final String username = author.map(User::getUsername).orElse("unknown");
                sendInsideMessage(insideChannel, discordChannel, username, contentWithMentions(message), message.getTimestamp().getEpochSecond());
            }
        };

        final Consumer<MessageUpdateEvent> updateMessage = event -> verticle.logConnectionEvent(() -> "Received an edited message from discord channel " + Long.toString(event.getChannelId().asLong()));

        gateway.on(MessageCreateEvent.class).subscribe(handleMessage);
        gateway.on(MessageUpdateEvent.class).subscribe(updateMessage);
    }

    private String contentWithMentions(Message message) {

        final String content = message.getContent();
        final String contentWithRoles = message
                .getRoleMentions()
                .reduce(content, (msg, role) -> msg.replaceAll("<@&?" + Long.toString(role.getId().asLong()) + ">", "@" + role.getName()))
                .block();

        final String contentWithUsers = message
                .getUserMentions()
                .reduce(contentWithRoles, (msg, role) -> msg.replaceAll("<@!?" + Long.toString(role.getId().asLong()) + ">", "@" + role.getUsername()))
                .block();

        Pattern regex = Pattern.compile("<#(\\d+)>");
        Matcher regexMatcher = regex.matcher(contentWithUsers);
        Map<String, String> channelMap = new HashMap<>();

        while (regexMatcher.find()) {
            final String channelId = regexMatcher.group(1);
            final String channelName;

            Channel channel = gateway.getChannelById(Snowflake.of(Long.parseLong(channelId))).block();

            if (channel instanceof GuildChannel) {
                channelName = ((GuildChannel)channel).getName();
            } else {
                channelName = channel.getMention();
            }

            channelMap.put(channelId, channelName);
        }

        return Arrays
                .stream(channelMap.keySet().toArray(new String[]{}))
                .reduce(contentWithUsers, (msg, id) -> msg.replaceAll("<#" + id + ">", "#" + channelMap.get(id)));
    }

    void sendOutsideMessage(String sourceChannel, String remoteChannel, String text, String fallbackText, String author, String authorLink, String authorColor, JsonArray additionalFields) {
        final MessageChannel channel = (MessageChannel) gateway.getChannelById(Snowflake.of(Long.parseLong(remoteChannel))).block();
        channel.createMessage("**" + author + "**: " + text).block();
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
