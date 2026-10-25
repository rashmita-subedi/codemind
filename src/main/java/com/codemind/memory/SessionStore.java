package com.codemind.memory;

import com.codemind.memory.ConversationTurn.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

// Append-only, per-session transcript log: <dir>/<id>.jsonl, one JSON object per turn.
// Makes conversation memory survive process restarts so a session can be resumed with
// --continue / --session. Best-effort: I/O failures degrade to in-memory-only rather
// than breaking the conversation. This is the filesystem boundary for memory, so it is
// the one place try/catch around I/O belongs.
@Component
public class SessionStore {

    private static final String EXT = ".jsonl";
    private static final String VALID_ID = "[A-Za-z0-9_-]{1,128}";

    private final Path dir;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public SessionStore(@Value("${codemind.memory.dir:.codemind/sessions}") String dir) {
        this.enabled = dir != null && !dir.isBlank();
        this.dir = this.enabled ? Path.of(dir) : null;
    }

    private SessionStore(boolean enabled, Path dir) {
        this.enabled = enabled;
        this.dir = dir;
    }

    // For tests / contexts that want memory without touching disk.
    public static SessionStore disabled() {
        return new SessionStore(false, null);
    }

    public void append(String sessionId, ConversationTurn turn) {
        if (!enabled || !valid(sessionId)) {
            return;
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("role", turn.role().name());
        node.put("content", turn.content());
        try {
            Files.createDirectories(dir);
            Files.writeString(fileFor(sessionId), node + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Persistence is best-effort; the live in-memory window remains the source of truth.
        }
    }

    public List<ConversationTurn> load(String sessionId) {
        if (!enabled || !valid(sessionId) || !Files.isRegularFile(fileFor(sessionId))) {
            return List.of();
        }
        List<ConversationTurn> turns = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(fileFor(sessionId), StandardCharsets.UTF_8)) {
                ConversationTurn turn = parse(line);
                if (turn != null) {
                    turns.add(turn);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return turns;
    }

    public Optional<String> latest() {
        return list().stream().findFirst();
    }

    // Saved session ids, most-recently-used first.
    public List<String> list() {
        if (!enabled || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(EXT))
                    .sorted(Comparator.comparingLong(SessionStore::lastModified).reversed())
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - EXT.length());
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public boolean exists(String sessionId) {
        return enabled && valid(sessionId) && Files.isRegularFile(fileFor(sessionId));
    }

    private ConversationTurn parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(line);
            JsonNode role = node.get("role");
            JsonNode content = node.get("content");
            if (role == null || content == null) {
                return null;
            }
            return new ConversationTurn(Role.valueOf(role.asText()), content.asText());
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    private Path fileFor(String sessionId) {
        return dir.resolve(sessionId + EXT);
    }

    private static boolean valid(String sessionId) {
        return sessionId != null && sessionId.matches(VALID_ID);
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
