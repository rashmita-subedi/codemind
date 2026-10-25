package com.codemind.cli;

import org.springframework.stereotype.Component;

/**
 * Application-scoped holder for the current {@link ConversationMode}.
 *
 * <p>{@link CliRunner} writes to this on {@code /a} (or the {@code agentic} / {@code ask}
 * commands); {@link com.codemind.service.ApprovalService} reads from it to decide whether
 * a destructive action needs an interactive y/N or can auto-apply.</p>
 *
 * <p>This replaces the previous "execution mode" (manual/auto) toggle: agentic mode
 * implies auto-apply, ask mode implies confirmation.</p>
 */
@Component
public class SessionMode {

    private volatile ConversationMode current = ConversationMode.ASK;

    public ConversationMode get() {
        return current;
    }

    public void set(ConversationMode mode) {
        this.current = mode == null ? ConversationMode.ASK : mode;
    }

    public boolean isAgentic() {
        return current == ConversationMode.AGENTIC;
    }

    public boolean isAsk() {
        return current == ConversationMode.ASK;
    }
}
