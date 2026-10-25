package com.codemind.memory;

public record ConversationTurn(Role role, String content) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public int charCount() {
        return content == null ? 0 : content.length();
    }
}
