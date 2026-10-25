package com.codemind.agent.core;

import com.codemind.policy.SafetyLevel;

public record ReActStep(
        int index,
        String thought,
        String action,
        String actionInput,
        String observation,
        SafetyLevel safetyLevel,
        boolean approved,
        long durationMs) {
}
