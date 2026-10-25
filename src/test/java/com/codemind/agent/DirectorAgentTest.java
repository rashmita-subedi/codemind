package com.codemind.agent;

import com.codemind.agent.IntentClassification.Args;
import com.codemind.agent.IntentClassification.ChainStep;
import com.codemind.agent.IntentClassification.Intent;
import com.codemind.agent.core.AgentResult;
import com.codemind.agent.core.ReActStep;
import com.codemind.policy.SafetyLevel;
import com.codemind.service.AuditService;
import com.codemind.service.PromptGuard;
import com.codemind.service.PromptGuardConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectorAgentTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private QnAAgent qna;
    private RefactorAgent refactor;
    private ChangeAnalyzerAgent change;
    private DirectorAgent director;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        qna = mock(QnAAgent.class);
        refactor = mock(RefactorAgent.class);
        change = mock(ChangeAnalyzerAgent.class);
        director = new DirectorAgent(chatClient, qna, refactor, change, new PromptGuard(new PromptGuardConfig(), new AuditService()));
    }

    private void classifyAs(IntentClassification classification) {
        when(callSpec.entity(IntentClassification.class)).thenReturn(classification);
    }

    private AgentResult result(String answer) {
        return new AgentResult(answer, List.of(), 0, 0L, false);
    }

    @Test
    void routesQna() {
        classifyAs(new IntentClassification(Intent.QNA, 0.95,
                new Args("where is auth", null, null, null), null));
        when(qna.ask("where is auth")).thenReturn(result("qna-answer"));

        AgentResult out = director.dispatch("where is auth");

        assertThat(out.answer()).isEqualTo("qna-answer");
        verify(qna).ask("where is auth");
    }

    @Test
    void qnaWithNullArgsAsksEmptyQuery() {
        classifyAs(new IntentClassification(Intent.QNA, 0.9, null, null));
        when(qna.ask("")).thenReturn(result("empty-qna"));

        AgentResult out = director.dispatch("");

        assertThat(out.answer()).isEqualTo("empty-qna");
        verify(qna).ask("");
    }

    @Test
    void routesRefactor() {
        classifyAs(new IntentClassification(Intent.REFACTOR, 0.97,
                new Args(null, "Foo.java", null, null), null));
        when(refactor.analyze("Foo.java")).thenReturn(result("refactor-plan"));

        AgentResult out = director.dispatch("refactor Foo.java");

        assertThat(out.answer()).isEqualTo("refactor-plan");
        verify(refactor).analyze("Foo.java");
    }

    @Test
    void refactorWithoutPathAsksForPath() {
        classifyAs(new IntentClassification(Intent.REFACTOR, 0.9,
                new Args(null, "  ", null, null), null));

        AgentResult out = director.dispatch("refactor something");

        assertThat(out.answer()).contains("needs a file path");
        verify(refactor, never()).analyze(anyString());
    }

    @Test
    void refactorWithNullArgsAsksForPath() {
        classifyAs(new IntentClassification(Intent.REFACTOR, 0.9, null, null));

        AgentResult out = director.dispatch("refactor");

        assertThat(out.answer()).contains("needs a file path");
        verify(refactor, never()).analyze(anyString());
    }

    @Test
    void refactorWithNullPathAsksForPath() {
        classifyAs(new IntentClassification(Intent.REFACTOR, 0.9,
                new Args(null, null, null, null), null));

        AgentResult out = director.dispatch("refactor");

        assertThat(out.answer()).contains("needs a file path");
        verify(refactor, never()).analyze(anyString());
    }

    @Test
    void routesCommit() {
        classifyAs(new IntentClassification(Intent.COMMIT, 0.95, new Args(null, null, null, null), null));
        when(change.generateCommitMessage()).thenReturn(result("feat: x"));

        AgentResult out = director.dispatch("commit");

        assertThat(out.answer()).isEqualTo("feat: x");
        verify(change).generateCommitMessage();
    }

    @Test
    void routesChangelogPlain() {
        classifyAs(new IntentClassification(Intent.CHANGELOG, 0.9,
                new Args(null, null, "v1.0..HEAD", Boolean.TRUE), null));
        when(change.generateChangelog("v1.0..HEAD", true)).thenReturn(result("notes"));

        AgentResult out = director.dispatch("release notes");

        assertThat(out.answer()).isEqualTo("notes");
        verify(change).generateChangelog("v1.0..HEAD", true);
    }

    @Test
    void changelogWithoutRangeAsksForRange() {
        classifyAs(new IntentClassification(Intent.CHANGELOG, 0.9,
                new Args(null, null, null, null), null));

        AgentResult out = director.dispatch("changelog");

        assertThat(out.answer()).contains("needs a git range");
        verify(change, never()).generateChangelog(anyString(), eq(false));
    }

    @Test
    void changelogWithNullArgsAsksForRange() {
        classifyAs(new IntentClassification(Intent.CHANGELOG, 0.9, null, null));

        AgentResult out = director.dispatch("changelog");

        assertThat(out.answer()).contains("needs a git range");
    }

    @Test
    void changelogWithBlankRangeAsksForRange() {
        classifyAs(new IntentClassification(Intent.CHANGELOG, 0.9,
                new Args(null, null, "  ", null), null));

        AgentResult out = director.dispatch("changelog");

        assertThat(out.answer()).contains("needs a git range");
    }

    @Test
    void runsCompoundChainInOrder() {
        ChainStep refactorStep = new ChainStep(Intent.REFACTOR, new Args(null, "Foo.java", null, null));
        ChainStep commitStep = new ChainStep(Intent.COMMIT, null);
        classifyAs(new IntentClassification(Intent.COMPOUND, 0.9, null,
                List.of(refactorStep, commitStep)));
        when(refactor.analyze("Foo.java")).thenReturn(new AgentResult("did-refactor",
                List.of(new ReActStep(1, "", "respond", "", "obs", SafetyLevel.SAFE, true, 5L)), 1, 5L, false));
        when(change.generateCommitMessage()).thenReturn(new AgentResult("did-commit",
                List.of(), 0, 3L, true));

        AgentResult out = director.dispatch("refactor Foo.java and commit");

        assertThat(out.answer()).contains("Step 1: REFACTOR").contains("did-refactor");
        assertThat(out.answer()).contains("Step 2: COMMIT").contains("did-commit");
        assertThat(out.totalSteps()).isEqualTo(1);
        assertThat(out.totalDurationMs()).isEqualTo(8L);
        assertThat(out.hitMaxSteps()).isTrue();
        verify(refactor).analyze("Foo.java");
        verify(change).generateCommitMessage();
    }

    @Test
    void compoundWithEmptyChainReturnsMessage() {
        classifyAs(new IntentClassification(Intent.COMPOUND, 0.9, null, List.of()));

        AgentResult out = director.dispatch("do nothing useful");

        assertThat(out.answer()).contains("no steps");
    }

    @Test
    void compoundWithNullChainReturnsMessage() {
        classifyAs(new IntentClassification(Intent.COMPOUND, 0.9, null, null));

        AgentResult out = director.dispatch("compound but empty");

        assertThat(out.answer()).contains("no steps");
    }

    @Test
    void compoundSkipsNullStep() {
        classifyAs(new IntentClassification(Intent.COMPOUND, 0.9, null,
                Arrays.asList((ChainStep) null, new ChainStep(Intent.COMMIT, null))));
        when(change.generateCommitMessage()).thenReturn(result("committed"));

        AgentResult out = director.dispatch("null then commit");

        assertThat(out.answer()).contains("Step 1: skipped");
        assertThat(out.answer()).contains("Step 2: COMMIT").contains("committed");
    }

    @Test
    void compoundSkipsUnrecognisedStep() {
        ChainStep bad = new ChainStep(null, null);
        ChainStep good = new ChainStep(Intent.COMMIT, null);
        classifyAs(new IntentClassification(Intent.COMPOUND, 0.9, null, List.of(bad, good)));
        when(change.generateCommitMessage()).thenReturn(result("committed"));

        AgentResult out = director.dispatch("garbage then commit");

        assertThat(out.answer()).contains("Step 1: skipped");
        assertThat(out.answer()).contains("Step 2: COMMIT").contains("committed");
    }

    @Test
    void lowConfidenceDoesNotRoute() {
        classifyAs(new IntentClassification(Intent.QNA, 0.3, new Args("huh", null, null, null), null));

        AgentResult out = director.dispatch("huh");

        assertThat(out.answer()).contains("Not confident enough");
        verify(qna, never()).ask(anyString());
    }

    @Test
    void nullClassificationReturnsMessage() {
        classifyAs(null);

        AgentResult out = director.dispatch("???");

        assertThat(out.answer()).contains("Could not understand");
    }

    @Test
    void nullIntentReturnsMessage() {
        classifyAs(new IntentClassification(null, 0.99, null, null));

        AgentResult out = director.dispatch("???");

        assertThat(out.answer()).contains("Could not understand");
    }

    @Test
    void classificationFailureReturnsMessage() {
        when(callSpec.entity(IntentClassification.class)).thenThrow(new RuntimeException("502 bad gateway"));

        AgentResult out = director.dispatch("anything");

        assertThat(out.answer()).contains("Could not classify");
        assertThat(out.answer()).contains("502 bad gateway");
    }
}
