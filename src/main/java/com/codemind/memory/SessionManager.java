package com.codemind.memory;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

// Holds the id of the conversation the CLI is currently inside. Agents read this
// instead of minting a fresh UUID per call, so successive turns share one session
// window. `reset()` rotates the id when the user clears the session (REPL `exit`).
@Component
public class SessionManager {

    private final AtomicReference<String> current = new AtomicReference<>(newId());

    public String currentSessionId() {
        return current.get();
    }

    public String reset() {
        String next = newId();
        current.set(next);
        return next;
    }

    // Adopts an existing session (e.g. --session / --continue). The id is normalised to a
    // filesystem-safe form so it can never escape the sessions directory.
    public String setCurrentSessionId(String sessionId) {
        String adopted = sanitize(sessionId);
        current.set(adopted);
        return adopted;
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return newId();
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_-]", "-");
        if (cleaned.length() > 128) {
            cleaned = cleaned.substring(0, 128);
        }
        return cleaned.isBlank() ? newId() : cleaned;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
