package com.codemind.agent.core;

import java.util.List;

public record AgentResult(
        String answer,
        List<ReActStep> steps,
        int totalSteps,
        long totalDurationMs,
        boolean hitMaxSteps) {

    public static AgentResult of(AgentContext context, long totalDurationMs, boolean hitMaxSteps) {
        List<ReActStep> snapshot = List.copyOf(context.steps());
        return new AgentResult(context.finalText(), snapshot, snapshot.size(), totalDurationMs, hitMaxSteps);
    }
}
