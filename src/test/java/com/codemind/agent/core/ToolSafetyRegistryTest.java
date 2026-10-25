package com.codemind.agent.core;

import com.codemind.policy.SafetyLevel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSafetyRegistryTest {

    @Test
    void containsAndLevelFor() {
        ToolSafetyRegistry registry = new ToolSafetyRegistry(Map.of(
                "search", SafetyLevel.SAFE,
                "write_file", SafetyLevel.CONFIRM_REQUIRED));

        assertThat(registry.contains("search")).isTrue();
        assertThat(registry.levelFor("search")).isEqualTo(SafetyLevel.SAFE);
        assertThat(registry.levelFor("write_file")).isEqualTo(SafetyLevel.CONFIRM_REQUIRED);
        assertThat(registry.contains("nope")).isFalse();
        assertThat(registry.levelFor("nope")).isNull();
    }
}
