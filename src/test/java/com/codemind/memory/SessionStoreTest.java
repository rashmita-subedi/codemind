package com.codemind.memory;

import com.codemind.memory.ConversationTurn.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    @TempDir
    Path tmp;

    private SessionStore store() {
        return new SessionStore(tmp.toString());
    }

    @Test
    void appendThenLoadRoundtrips() {
        SessionStore store = store();
        store.append("abc", new ConversationTurn(Role.USER, "hello"));
        store.append("abc", new ConversationTurn(Role.ASSISTANT, "hi there"));

        List<ConversationTurn> loaded = store.load("abc");
        assertThat(loaded).extracting(ConversationTurn::role)
                .containsExactly(Role.USER, Role.ASSISTANT);
        assertThat(loaded).extracting(ConversationTurn::content)
                .containsExactly("hello", "hi there");
    }

    @Test
    void loadMissingSessionReturnsEmpty() {
        assertThat(store().load("does-not-exist")).isEmpty();
    }

    @Test
    void existsReflectsWhetherFileWasWritten() {
        SessionStore store = store();
        assertThat(store.exists("s")).isFalse();

        store.append("s", new ConversationTurn(Role.USER, "x"));

        assertThat(store.exists("s")).isTrue();
    }

    @Test
    void latestPicksMostRecentlyModifiedSession() throws IOException {
        SessionStore store = store();
        store.append("old", new ConversationTurn(Role.USER, "a"));
        store.append("new", new ConversationTurn(Role.USER, "b"));
        Files.setLastModifiedTime(tmp.resolve("old.jsonl"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(tmp.resolve("new.jsonl"), FileTime.fromMillis(2_000));

        assertThat(store.latest()).contains("new");
    }

    @Test
    void latestIsEmptyWhenNoSessionsExist() {
        assertThat(store().latest()).isEmpty();
    }

    @Test
    void listReturnsSessionsMostRecentFirst() throws IOException {
        SessionStore store = store();
        store.append("alpha", new ConversationTurn(Role.USER, "a"));
        store.append("beta", new ConversationTurn(Role.USER, "b"));
        Files.setLastModifiedTime(tmp.resolve("alpha.jsonl"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(tmp.resolve("beta.jsonl"), FileTime.fromMillis(2_000));

        assertThat(store.list()).containsExactly("beta", "alpha");
    }

    @Test
    void listIsEmptyForDisabledStore() {
        assertThat(SessionStore.disabled().list()).isEmpty();
    }

    @Test
    void skipsMalformedAndBlankLines() throws IOException {
        SessionStore store = store();
        store.append("s", new ConversationTurn(Role.USER, "good"));
        Files.writeString(tmp.resolve("s.jsonl"),
                "not-json\n\n{\"role\":\"BOGUS\",\"content\":\"x\"}\n{\"content\":\"no role\"}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThat(store.load("s")).extracting(ConversationTurn::content).containsExactly("good");
    }

    @Test
    void rejectsPathTraversalIds() {
        SessionStore store = store();
        store.append("../escape", new ConversationTurn(Role.USER, "x"));

        assertThat(store.load("../escape")).isEmpty();
        assertThat(store.exists("../escape")).isFalse();
    }

    @Test
    void disabledStoreIsANoOp() {
        SessionStore disabled = SessionStore.disabled();
        disabled.append("s", new ConversationTurn(Role.USER, "x"));

        assertThat(disabled.load("s")).isEmpty();
        assertThat(disabled.latest()).isEmpty();
        assertThat(disabled.exists("s")).isFalse();
    }

    @Test
    void blankDirectoryDisablesPersistence() {
        SessionStore blank = new SessionStore("   ");
        blank.append("s", new ConversationTurn(Role.USER, "x"));

        assertThat(blank.load("s")).isEmpty();
    }
}
