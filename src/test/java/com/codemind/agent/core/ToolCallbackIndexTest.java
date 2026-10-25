package com.codemind.agent.core;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolCallbackIndexTest {

    @Test
    void lookupAndAll() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolCallbackIndex index = new ToolCallbackIndex(Map.of("search", callback));

        assertThat(index.callbackFor("search")).isSameAs(callback);
        assertThat(index.callbackFor("nope")).isNull();
        assertThat(index.all()).containsExactly(callback);
    }
}
