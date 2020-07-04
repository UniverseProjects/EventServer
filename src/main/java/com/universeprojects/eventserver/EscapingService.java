package com.universeprojects.eventserver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("RegExpRedundantEscape")
public class EscapingService {
    public static final EscapingService INSTANCE = new EscapingService();

    private final Pattern linkPattern = Pattern.compile("<\\s*a\\s+href=\"([^\"]*)\"\\s*>([^<]+)<\\/a\\s*>");
    private final Pattern boldPattern = Pattern.compile("<\\s*b\\s*>([^<]+)<\\/b\\s*>");
    private final Pattern strongPattern = Pattern.compile("<\\s*strong\\s*>([^<]+)<\\/strong\\s*>");
    private final Pattern italicPattern = Pattern.compile("<\\s*i\\s*>([^<]+)<\\/i\\s*>");
    private final Pattern emPattern = Pattern.compile("<\\s*em\\s*>([^<]+)<\\/em\\s*>");

    public String escapeHtml(String message, boolean translateToMarkdown) {
        String text = message;
        final Matcher linkMatcher = linkPattern.matcher(text);
        if(translateToMarkdown) {
            text = linkMatcher.replaceAll("!!l!!$1|$2!!g!!");
        } else {
            text = linkMatcher.replaceAll("$2");
        }

        final Matcher boldMatcher = boldPattern.matcher(text);
        if(translateToMarkdown) {
            text = boldMatcher.replaceAll("*$1*");
        } else {
            text = boldMatcher.replaceAll("$1");
        }

        final Matcher strongMatcher = strongPattern.matcher(text);
        if(translateToMarkdown) {
            text = strongMatcher.replaceAll("*$1*");
        } else {
            text = strongMatcher.replaceAll("$1");
        }

        final Matcher italicMatcher = italicPattern.matcher(text);
        if(translateToMarkdown) {
            text = italicMatcher.replaceAll("_$1_");
        } else {
            text = italicMatcher.replaceAll("$1");
        }

        final Matcher emMatcher = emPattern.matcher(text);
        if(translateToMarkdown) {
            text = emMatcher.replaceAll("_$1_");
        } else {
            text = emMatcher.replaceAll("$1");
        }

        if(translateToMarkdown) {
            text = text.replaceAll("<\\s*br\\s*/?>", "\\n");
        } else {
            text = text.replaceAll("<\\s*br\\s*/?>", "");
        }

        text = escapeAllHtml(text);

        if(translateToMarkdown) {
            text = text.replaceAll("!!l!!", "<");
            text = text.replaceAll("!!g!!", ">");
        }

        return text;
    }

    public String escapeAllHtml(String message) {
        return message.
            replaceAll("&", "%amp;").
            replaceAll("<", "&lt;").
            replaceAll(">","&gt;");
    }

    public ChatMessage escapeHtml(ChatMessage chatMessage, boolean translateToMarkdown) {
        chatMessage.text = escapeHtml(chatMessage.text, translateToMarkdown);
        return chatMessage;
    }
}
