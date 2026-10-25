package com.codemind.agent;

import com.codemind.memory.ConversationMemoryService;
import com.codemind.memory.SessionManager;
import com.codemind.memory.SessionStore;
import com.codemind.model.CodeChunk;
import com.codemind.retrieval.ChunkStore;
import com.codemind.retrieval.LanguagePatterns;
import com.codemind.service.AuditService;
import com.codemind.service.PromptGuard;
import com.codemind.service.PromptGuardConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReActEngineTest {

    private ChunkStore chunkStore;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ConversationMemoryService memory;
    private SessionManager sessions;
    private ReActEngine engine;

    @BeforeEach
    void setUp() {
        chunkStore = mock(ChunkStore.class);
        LanguagePatterns patterns = mock(LanguagePatterns.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(chunkStore.summary()).thenReturn("index summary");
        when(chunkStore.navigationDigest(anyString())).thenReturn("nav digest");

        memory = new ConversationMemoryService(20, 8000,
                new PromptGuard(new PromptGuardConfig(), new AuditService()), SessionStore.disabled());
        sessions = new SessionManager();
        engine = new ReActEngine(chunkStore, patterns, builder, memory, sessions);
    }

    private CodeChunk chunk() {
        return new CodeChunk("Foo.java", "svc", "controller", 1, 9, "return ok();");
    }

    @Test
    void recordsExchangeOnSuccessfulAnswer() {
        when(chunkStore.searchForCodebaseQuestion(anyString(), anyInt())).thenReturn(List.of(chunk()));
        when(callSpec.content()).thenReturn("the cited answer [Foo.java:1]");

        ReActResult result = engine.answer("where is auth");

        assertThat(result.answer()).contains("cited answer");
        assertThat(memory.transcript(sessions.currentSessionId()))
                .contains("User: where is auth")
                .contains("CodeMind: the cited answer [Foo.java:1]");
    }

    @Test
    void weavesPriorTurnsIntoTheFollowUpPrompt() {
        when(chunkStore.searchForCodebaseQuestion(anyString(), anyInt())).thenReturn(List.of(chunk()));
        when(callSpec.content()).thenReturn("first answer", "second answer");

        engine.answer("first question");
        engine.answer("second question");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(requestSpec, org.mockito.Mockito.atLeast(2)).user(userPrompt.capture());
        String followUp = userPrompt.getAllValues().get(userPrompt.getAllValues().size() - 1);
        assertThat(followUp)
                .contains("User: first question")
                .contains("CodeMind: first answer")
                .contains("second question");
    }

    @Test
    void recordsExchangeWhenNoContextFound() {
        when(chunkStore.searchForCodebaseQuestion(anyString(), anyInt())).thenReturn(List.of());

        ReActResult result = engine.answer("unknown thing");

        assertThat(result.answer()).contains("No matching indexed context");
        assertThat(memory.transcript(sessions.currentSessionId()))
                .contains("User: unknown thing")
                .contains("CodeMind: No matching indexed context");
    }
}
