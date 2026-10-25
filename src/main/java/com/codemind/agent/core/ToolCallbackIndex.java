package com.codemind.agent.core;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

// Wraps the tool-name -> ToolCallback map in a single bean type so the executor can
// inject it unambiguously; a raw Map<String, ToolCallback> bean would collide with
// Spring's by-type Map autowiring (which keys all ToolCallback beans by bean name).
public record ToolCallbackIndex(Map<String, ToolCallback> byName) {

    public ToolCallbackIndex {
        byName = Map.copyOf(byName);
    }

    public ToolCallback callbackFor(String toolName) {
        return byName.get(toolName);
    }

    public List<ToolCallback> all() {
        return List.copyOf(byName.values());
    }
}
