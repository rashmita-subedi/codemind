package com.codemind.agent.core;

import com.codemind.policy.SafetyLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReActStepTest {

    @Test
    void roundTripsAllFields() {
        ReActStep step = new ReActStep(1, "thought", "search", "{}", "obs", SafetyLevel.PREVIEW, true, 42L);

        assertThat(step.index()).isEqualTo(1);
        assertThat(step.thought()).isEqualTo("thought");
        assertThat(step.action()).isEqualTo("search");
        assertThat(step.actionInput()).isEqualTo("{}");
        assertThat(step.observation()).isEqualTo("obs");
        assertThat(step.safetyLevel()).isEqualTo(SafetyLevel.PREVIEW);
        assertThat(step.approved()).isTrue();
        assertThat(step.durationMs()).isEqualTo(42L);
    }

    @Test
    void recordEqualityOnIdenticalFields() {
        ReActStep a = new ReActStep(1, "t", "a", "i", "o", SafetyLevel.SAFE, false, 0L);
        ReActStep b = new ReActStep(1, "t", "a", "i", "o", SafetyLevel.SAFE, false, 0L);

        assertThat(a).isEqualTo(b);
    }
}
