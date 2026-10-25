package com.codemind.agent.core;

import com.codemind.memory.ConversationMemoryService;
import com.codemind.memory.SessionStore;
import com.codemind.policy.SafetyLevel;
import com.codemind.service.ApprovalService;
import com.codemind.service.AuditService;
import com.codemind.service.PromptGuard;
import com.codemind.service.PromptGuardConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentExecutorTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ApprovalService approvals;
    private AuditService audit;
    private ToolCallback searchCb;
    private PromptGuard guard;
    private ToolSafetyRegistry safety;
    private ToolCallbackIndex index;
    private ConversationMemoryService memory;
    private AgentExecutor executor;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        approvals = mock(ApprovalService.class);
        audit = mock(AuditService.class);
        searchCb = mock(ToolCallback.class);
        guard = new PromptGuard(new PromptGuardConfig(), audit);
        safety = new ToolSafetyRegistry(Map.of(
                "search", SafetyLevel.SAFE,
                "write_file", SafetyLevel.CONFIRM_REQUIRED));
        index = new ToolCallbackIndex(Map.of("search", searchCb));
        memory = new ConversationMemoryService(20, 8000, guard, SessionStore.disabled());
        executor = new AgentExecutor(guard, approvals, audit, safety, index, memory);
    }

    private ChatResponse toolTurn(String text, String tool, String argsJson) {
        ToolCall call = new ToolCall("id-" + tool, "function", tool, argsJson);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text, Map.of(), List.of(call)))));
    }

    private ChatResponse textTurn(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatResponse twoToolTurn() {
        ToolCall a = new ToolCall("id-a", "function", "search", "{\"query\":\"a\"}");
        ToolCall b = new ToolCall("id-b", "function", "search", "{\"query\":\"b\"}");
        return new ChatResponse(List.of(new Generation(new AssistantMessage("two", Map.of(), List.of(a, b)))));
    }

    @Test
    void immediateAnswer_noTools() {
        when(callSpec.chatResponse()).thenReturn(textTurn("Here is the answer [File.java:1]."));

        AgentResult result = executor.execute(chatClient, new AgentContext("s1", "question"));

        assertThat(result.answer()).contains("Here is the answer");
        assertThat(result.totalSteps()).isEqualTo(1);
        assertThat(result.steps().get(0).action()).isEqualTo("respond");
        assertThat(result.hitMaxSteps()).isFalse();
    }

    @Test
    void oneSafeToolThenAnswer() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(
                toolTurn("let me search", "search", "{\"query\":\"x\",\"topK\":8}"),
                textTurn("Answer [F.java:2]"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s2", "q"));

        ReActStep toolStep = result.steps().get(0);
        assertThat(toolStep.action()).isEqualTo("search");
        assertThat(toolStep.safetyLevel()).isEqualTo(SafetyLevel.SAFE);
        assertThat(toolStep.approved()).isTrue();
        assertThat(result.answer()).contains("Answer");
        verify(searchCb).call(anyString());
        verify(audit).record(eq("search.applied"), anyString());
    }

    @Test
    void confirmRequiredDeclined() {
        when(approvals.approved(any())).thenReturn(false);
        when(callSpec.chatResponse()).thenReturn(
                toolTurn("want to write", "write_file", "{\"path\":\"x\"}"),
                textTurn("did it another way"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s3", "q"));

        ReActStep step = result.steps().get(0);
        assertThat(step.action()).isEqualTo("write_file");
        assertThat(step.approved()).isFalse();
        assertThat(step.observation()).startsWith("User declined");
        assertThat(result.answer()).contains("another way");
        verify(audit).record(contains("rejected"), any());
        verify(searchCb, never()).call(any());
    }

    @Test
    void maxStepsGuardrail() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(toolTurn("loop", "search", "{\"query\":\"x\"}"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s4", "q"));

        long toolSteps = result.steps().stream().filter(s -> s.action().equals("search")).count();
        assertThat(toolSteps).isEqualTo(10);
        assertThat(result.hitMaxSteps()).isTrue();
        assertThat(result.answer()).contains("step limit");
        assertThat(result.answer()).doesNotContain("self-reflection injected");
        verify(audit).record(eq("agent.maxsteps"), any());
    }

    @Test
    void selfReflectionEveryThreeSteps() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(toolTurn("loop", "search", "{}"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s5", "q"));

        ReActStep reflect = result.steps().stream()
                .filter(step -> step.action().equals("reflect"))
                .findFirst()
                .orElseThrow();
        assertThat(reflect.safetyLevel()).isEqualTo(SafetyLevel.SAFE);
    }

    @Test
    void textFallbackParse() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(
                textTurn("{\"tool\":\"search\",\"args\":{\"query\":\"x\",\"topK\":8}}"),
                textTurn("final"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s6", "q"));

        assertThat(result.steps()).anyMatch(step -> step.action().equals("fallback_parse"));
        assertThat(result.answer()).isEqualTo("final");
        verify(searchCb).call(anyString());
    }

    @Test
    void promptGuardSanitizesArgsAndResults() {
        PromptGuard spyGuard = spy(new PromptGuard(new PromptGuardConfig(), audit));
        AgentExecutor spied = new AgentExecutor(spyGuard, approvals, audit, safety, index,
                new ConversationMemoryService(20, 8000, spyGuard, SessionStore.disabled()));
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(
                toolTurn("t", "search", "{\"query\":\"x\"}"),
                textTurn("done"));

        spied.execute(chatClient, new AgentContext("s7", "q"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(spyGuard, atLeast(2)).sanitize(captor.capture());
        assertThat(captor.getAllValues()).contains("RESULT");
        assertThat(captor.getAllValues()).contains("{\"query\":\"x\"}");
    }

    @Test
    void unknownToolHallucination() {
        when(callSpec.chatResponse()).thenReturn(
                toolTurn("t", "delete_universe", "{}"),
                textTurn("recovered"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s8", "q"));

        ReActStep step = result.steps().get(0);
        assertThat(step.approved()).isFalse();
        assertThat(step.observation()).isEqualTo("Unknown tool: delete_universe");
        assertThat(result.answer()).isEqualTo("recovered");
        verify(audit).record(eq("tool.unknown"), eq("delete_universe"));
        verify(searchCb, never()).call(any());
    }

    @Test
    void endpointFailureDegradesGracefully() {
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("503 Service Unavailable"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s9", "q"));

        assertThat(result.answer()).contains("model endpoint failed");
        assertThat(result.hitMaxSteps()).isFalse();
        verify(audit).record(eq("llm.error"), anyString());
    }

    @Test
    void modelReturnsNull() {
        when(callSpec.chatResponse()).thenReturn(null);

        AgentResult result = executor.execute(chatClient, new AgentContext("s10", "q"));

        assertThat(result.answer()).contains("empty response");
        assertThat(result.hitMaxSteps()).isFalse();
    }

    @Test
    void modelReturnsEmptyResults() {
        when(callSpec.chatResponse()).thenReturn(new ChatResponse(List.of()));

        AgentResult result = executor.execute(chatClient, new AgentContext("s11", "q"));

        assertThat(result.answer()).contains("empty response");
        assertThat(result.hitMaxSteps()).isFalse();
    }

    @Test
    void textFallbackParsesFencedJson() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(
                textTurn("```json\n{\"tool\":\"search\",\"args\":{\"query\":\"x\"}}\n```"),
                textTurn("final"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s12", "q"));

        assertThat(result.steps()).anyMatch(step -> step.action().equals("fallback_parse"));
        assertThat(result.answer()).isEqualTo("final");
        verify(searchCb).call(anyString());
    }

    @Test
    void toolBodyThrowingIsAuditedAsErrorNotApplied() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenThrow(new RuntimeException("boom"));
        when(callSpec.chatResponse()).thenReturn(
                toolTurn("t", "search", "{\"query\":\"x\"}"),
                textTurn("recovered"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s13", "q"));

        ReActStep step = result.steps().get(0);
        assertThat(step.observation()).startsWith("Tool search failed");
        assertThat(step.approved()).isFalse();
        assertThat(result.answer()).isEqualTo("recovered");
        verify(audit).record(eq("search.error"), anyString());
        verify(audit, never()).record(eq("search.applied"), anyString());
    }

    @Test
    void toolDispatchIsBracketedByPreApprovedScope() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(
                toolTurn("t", "search", "{\"query\":\"x\"}"),
                textTurn("done"));

        executor.execute(chatClient, new AgentContext("s-scope", "q"));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(approvals, searchCb);
        inOrder.verify(approvals).approved(any());
        inOrder.verify(approvals).enterPreApproved();
        inOrder.verify(searchCb).call(anyString());
        inOrder.verify(approvals).exitPreApproved();
    }

    @Test
    void preApprovedScopeIsClosedEvenWhenToolThrows() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenThrow(new RuntimeException("boom"));
        when(callSpec.chatResponse()).thenReturn(
                toolTurn("t", "search", "{\"query\":\"x\"}"),
                textTurn("recovered"));

        executor.execute(chatClient, new AgentContext("s-scope2", "q"));

        verify(approvals).enterPreApproved();
        verify(approvals).exitPreApproved();
    }

    @Test
    void multipleToolCallsInOneTurn() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(twoToolTurn(), textTurn("done"));

        AgentResult result = executor.execute(chatClient, new AgentContext("s14", "q"));

        long toolSteps = result.steps().stream().filter(s -> s.action().equals("search")).count();
        assertThat(toolSteps).isEqualTo(2);
        assertThat(result.answer()).isEqualTo("done");
        verify(searchCb, times(2)).call(anyString());
    }

    @Test
    void recordsExchangeIntoSessionMemory() {
        when(callSpec.chatResponse()).thenReturn(textTurn("the answer [F.java:1]"));

        executor.execute(chatClient, new AgentContext("mem-1", "what is auth"));

        assertThat(memory.turnCount("mem-1")).isEqualTo(2);
        assertThat(memory.transcript("mem-1"))
                .contains("User: what is auth")
                .contains("CodeMind: the answer [F.java:1]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void seedsPriorTurnsIntoFirstModelCall() {
        memory.recordExchange("mem-2", "earlier question", "earlier answer");
        when(callSpec.chatResponse()).thenReturn(textTurn("follow-up answer"));

        executor.execute(chatClient, new AgentContext("mem-2", "follow up"));

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec, atLeast(1)).messages(captor.capture());
        List<Message> firstCall = captor.getAllValues().get(0);
        assertThat(firstCall).hasSize(3);
        assertThat(firstCall.get(0).getText()).isEqualTo("earlier question");
        assertThat(firstCall.get(1).getText()).isEqualTo("earlier answer");
        assertThat(firstCall.get(2).getText()).isEqualTo("follow up");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolResponseEchoesToolCallId() {
        when(approvals.approved(any())).thenReturn(true);
        when(searchCb.call(anyString())).thenReturn("RESULT");
        when(callSpec.chatResponse()).thenReturn(
                toolTurn("t", "search", "{\"query\":\"x\"}"),
                textTurn("done"));

        executor.execute(chatClient, new AgentContext("s15", "q"));

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec, atLeast(2)).messages(captor.capture());
        ToolResponseMessage toolMessage = captor.getAllValues().stream()
                .flatMap(List::stream)
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .findFirst()
                .orElseThrow();
        ToolResponseMessage.ToolResponse response = toolMessage.getResponses().get(0);
        assertThat(response.id()).isEqualTo("id-search");
        assertThat(response.name()).isEqualTo("search");
    }
}
