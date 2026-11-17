package org.telegram.messenger.jarvis;

import java.util.Locale;

/** Shared command matching for the native mobile settings handoff. */
public final class JarvisCommandText {
    private JarvisCommandText() {}

    /**
     * /setkey is the legacy chat command for a hosted key. On mobile it opens
     * the native settings page instead of sending the command: a key typed
     * after it would otherwise remain in the chat history. The match is exact
     * so ordinary text containing "/setkey" is never swallowed.
     */
    /**
     * /ask is the in-chat local-question command. Returns the question (which
     * may be empty when the user just typed "/ask") or null when the text is
     * not that command. Only used in group/channel chats.
     */
    public static String askQuestion(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("/ask")) {
            return null;
        }
        String rest = trimmed.substring("/ask".length());
        if (!rest.isEmpty()) {
            char first = rest.charAt(0);
            if (first != ' ' && first != '\n' && first != '\t' && first != '@') {
                return null;
            }
        }
        String question = rest.trim();
        if (question.startsWith("@")) {
            int space = question.indexOf(' ');
            question = space >= 0 ? question.substring(space + 1).trim() : "";
        }
        if (question.length() > 1000) {
            question = question.substring(0, 1000);
        }
        return question;
    }

    public static boolean isSetKeyCommand(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("/setkey")) {
            return false;
        }
        String rest = trimmed.substring("/setkey".length());
        if (rest.isEmpty()) {
            return true;
        }
        char first = rest.charAt(0);
        if (first != ' ' && first != '\n' && first != '\t' && first != '@') {
            return false;
        }
        // Custom endpoints still use the chat command: the native form only
        // offers known providers and must not silently remove that capability.
        String[] parts = rest.trim().split("\\s+");
        int start = 0;
        if (parts.length > 0 && parts[0].startsWith("@")) {
            start = 1;
        }
        return !(parts.length > start && parts[start].equalsIgnoreCase("custom"));
    }
}
