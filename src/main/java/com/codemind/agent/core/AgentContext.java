package com.codemind.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Mutable by design: the ReAct loop accumulates steps in place and flips
// finished/finalText on completion, so this is not a record.
public final class AgentContext {

    private final String sessionId;
    private final String originalInput;
    private final int maxSteps;
    private final List<ReActStep> steps = new ArrayList<>();
    private boolean finished;
    private String finalText = "";

    public AgentContext(String sessionId, String originalInput) {
        this(sessionId, originalInput, 10);
    }

    public AgentContext(String sessionId, String originalInput, int maxSteps) {
        this.sessionId = sessionId;
        this.originalInput = originalInput;
        this.maxSteps = maxSteps;
    }

    public String sessionId() {
        return sessionId;
    }

    public String originalInput() {
        return originalInput;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public List<ReActStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    public boolean finished() {
        return finished;
    }

    public String finalText() {
        return finalText;
    }

    public void addStep(ReActStep step) {
        steps.add(step);
    }

    public void markFinished(String finalText) {
        this.finished = true;
        this.finalText = finalText;
    }
}
