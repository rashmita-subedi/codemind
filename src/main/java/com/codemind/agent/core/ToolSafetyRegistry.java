package com.codemind.agent.core;

import com.codemind.policy.SafetyLevel;

import java.util.Map;

public record ToolSafetyRegistry(Map<String, SafetyLevel> levels) {

    public ToolSafetyRegistry {
        levels = Map.copyOf(levels);
    }

    public boolean contains(String toolName) {
        return levels.containsKey(toolName);
    }

    public SafetyLevel levelFor(String toolName) {
        return levels.get(toolName);
    }
}
