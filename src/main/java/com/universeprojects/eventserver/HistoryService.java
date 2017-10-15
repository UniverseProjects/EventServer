package com.universeprojects.eventserver;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public interface HistoryService {
    void storeChatHistory(String channel, int historySize, List<ChatMessage> messages);
    void fetchHistoryMessages(Set<String> channels, int historySize, BiConsumer<String, List<ChatMessage>> messageHandler);

}
