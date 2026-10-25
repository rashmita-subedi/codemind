package com.codemind.agent.core;

import com.codemind.policy.SafetyLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentContextTest {

    private ReActStep aStep() {
        return new ReActStep(1, "", "action", "", "obs", SafetyLevel.SAFE, true, 0L);
    }

    @Test
    void defaultsMaxStepsToTen() {
        assertThat(new AgentContext("s", "input").maxSteps()).isEqualTo(10);
    }

    @Test
    void honoursExplicitMaxSteps() {
        assertThat(new AgentContext("s", "input", 5).maxSteps()).isEqualTo(5);
    }

    @Test
    void echoesConstructorArguments() {
        AgentContext context = new AgentContext("sess", "the input");

        assertThat(context.sessionId()).isEqualTo("sess");
        assertThat(context.originalInput()).isEqualTo("the input");
    }

    @Test
    void addStepGrowsSteps() {
        AgentContext context = new AgentContext("s", "input");

        context.addStep(aStep());

        assertThat(context.steps()).hasSize(1);
    }

    @Test
    void markFinishedSetsFlagAndText() {
        AgentContext context = new AgentContext("s", "input");

        context.markFinished("done");

        assertThat(context.finished()).isTrue();
        assertThat(context.finalText()).isEqualTo("done");
    }

    @Test
    void stepsViewIsUnmodifiable() {
        AgentContext context = new AgentContext("s", "input");

        assertThatThrownBy(() -> context.steps().add(aStep()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
