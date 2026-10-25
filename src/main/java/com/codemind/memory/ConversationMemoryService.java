package com.codemind.memory;

import com.codemind.memory.ConversationTurn.Role;
import com.codemind.service.PromptGuard;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Session-scoped working memory: a bounded sliding window of recent turns per session.
// Doubly bounded — by turn count (recall depth) and by total characters (context-token
// budget) — so a few large turns cannot silently blow the prompt size. Sanitizes on the
// way in, mirroring the LongTermMemoryService convention: stored content is already safe.
@Service
public class ConversationMemoryService {

    private final int maxTurns;
    private final int maxChars;
    private final PromptGuard guard;
    private final SessionStore store;
    private final Map<String, Window> sessions = new ConcurrentHashMap<>();

    public ConversationMemoryService(
            @Value("${codemind.memory.window:20}") int maxTurns,
            @Value("${codemind.memory.max-chars:8000}") int maxChars,
            PromptGuard guard,
            SessionStore store) {
        this.maxTurns = Math.max(1, maxTurns);
        this.maxChars = Math.max(1, maxChars);
        this.guard = guard;
        this.store = store;
    }

    public void record(String sessionId, Role role, String content) {
        if (sessionId == null || role == null || content == null || content.isBlank()) {
            return;
        }
        ConversationTurn turn = new ConversationTurn(role, guard.sanitize(content));
        addToWindow(sessionId, turn);
        store.append(sessionId, turn);
    }

    // Rebuilds a session's in-memory window from its persisted transcript so it can be
    // resumed across restarts. Re-sanitizes on load: a transcript file is untrusted input.
    public int resume(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        Window window = new Window();
        for (ConversationTurn turn : store.load(sessionId)) {
            window.add(new ConversationTurn(turn.role(), guard.sanitize(turn.content())), maxTurns, maxChars);
        }
        int restored = window.size();
        sessions.put(sessionId, window);
        return restored;
    }

    public Optional<String> latestSession() {
        return store.latest();
    }

    public List<String> listSessions() {
        return store.list();
    }

    private void addToWindow(String sessionId, ConversationTurn turn) {
        Window window = sessions.computeIfAbsent(sessionId, key -> new Window());
        synchronized (window) {
            window.add(turn, maxTurns, maxChars);
        }
    }

    public void recordExchange(String sessionId, String userInput, String assistantAnswer) {
        record(sessionId, Role.USER, userInput);
        record(sessionId, Role.ASSISTANT, assistantAnswer);
    }

    public List<ConversationTurn> history(String sessionId) {
        Window window = sessions.get(sessionId);
        if (window == null) {
            return List.of();
        }
        synchronized (window) {
            return window.snapshot();
        }
    }

    // Spring AI message view, for seeding the AgentExecutor ReAct loop. Stored turns are
    // plain text (no tool calls), so replaying them as User/Assistant messages is safe.
    public List<Message> messages(String sessionId) {
        List<ConversationTurn> turns = history(sessionId);
        List<Message> out = new ArrayList<>(turns.size());
        for (ConversationTurn turn : turns) {
            out.add(turn.role() == Role.USER
                    ? new UserMessage(turn.content())
                    : new AssistantMessage(turn.content()));
        }
        return out;
    }

    // Compact transcript for prompt templates that take a single string (e.g. ReActEngine).
    public String transcript(String sessionId) {
        List<ConversationTurn> turns = history(sessionId);
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ConversationTurn turn : turns) {
            sb.append(turn.role() == Role.USER ? "User: " : "CodeMind: ")
              .append(turn.content())
              .append('\n');
        }
        return sb.toString().strip();
    }

    public int turnCount(String sessionId) {
        Window window = sessions.get(sessionId);
        if (window == null) {
            return 0;
        }
        synchronized (window) {
            return window.size();
        }
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    // One session's window. Keeps a running character total so eviction is O(1) amortized
    // per insert instead of re-summing the whole window each time. Not thread-safe on its
    // own — the service synchronizes on the instance.
    private static final class Window {

        private final Deque<ConversationTurn> turns = new ArrayDeque<>();
        private int chars;

        void add(ConversationTurn turn, int maxTurns, int maxChars) {
            turns.addLast(turn);
            chars += turn.charCount();
            while (turns.size() > maxTurns) {
                chars -= turns.removeFirst().charCount();
            }
            while (chars > maxChars && turns.size() > 1) {
                chars -= turns.removeFirst().charCount();
            }
        }

        List<ConversationTurn> snapshot() {
            return List.copyOf(turns);
        }

        int size() {
            return turns.size();
        }
    }
}
