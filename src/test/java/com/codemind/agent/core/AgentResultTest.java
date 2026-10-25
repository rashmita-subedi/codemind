package com.codemind.agent.core;

import com.codemind.policy.SafetyLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentResultTest {

    private ReActStep step(int index) {
        return new ReActStep(index, "", "action", "", "obs", SafetyLevel.SAFE, true, 1L);
    }

    @Test
    void ofDerivesAnswerAndTotals() {
        AgentContext context = new AgentContext("s", "input");
        context.addStep(step(1));
        context.markFinished("the answer");

        AgentResult result = AgentResult.of(context, 99L, false);

        assertThat(result.answer()).isEqualTo("the answer");
        assertThat(result.totalSteps()).isEqualTo(1);
        assertThat(result.totalDurationMs()).isEqualTo(99L);
        assertThat(result.hitMaxSteps()).isFalse();
    }

    @Test
    void ofSnapshotsStepsImmutably() {
        AgentContext context = new AgentContext("s", "input");
        context.addStep(step(1));
        context.markFinished("answer");

        AgentResult result = AgentResult.of(context, 1L, false);
        context.addStep(step(2));

        assertThat(result.steps()).hasSize(1);
        assertThatThrownBy(() -> result.steps().add(step(3)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
