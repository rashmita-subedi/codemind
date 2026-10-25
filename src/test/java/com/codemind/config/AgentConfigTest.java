package com.codemind.config;

import com.codemind.agent.core.ToolCallbackIndex;
import com.codemind.agent.core.ToolSafetyRegistry;
import com.codemind.policy.SafeTool;
import com.codemind.policy.SafetyLevel;
import com.codemind.retrieval.ChunkStore;
import com.codemind.tool.CodeSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConfigTest {

    private final AgentConfig config = new AgentConfig();

    @Test
    void failsFastWhenToolMissingSafeTool() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeanDefinitionNames()).thenReturn(new String[]{"bad"});
        doReturn(BadTool.class).when(context).getType("bad");
        when(context.getBean("bad")).thenReturn(new BadTool());

        assertThatThrownBy(() -> config.toolSafetyRegistry(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing @SafeTool");
    }

    @Test
    void resolvesSafetyLevelsAndCallbacksForRealTool() {
        ChunkStore chunkStore = mock(ChunkStore.class);
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeanDefinitionNames()).thenReturn(new String[]{"codeSearchTool"});
        doReturn(CodeSearchTool.class).when(context).getType("codeSearchTool");
        when(context.getBean("codeSearchTool")).thenReturn(new CodeSearchTool(chunkStore));

        ToolSafetyRegistry registry = config.toolSafetyRegistry(context);
        assertThat(registry.levelFor("code_search")).isEqualTo(SafetyLevel.SAFE);
        assertThat(registry.levelFor("navigation_map")).isEqualTo(SafetyLevel.SAFE);

        ToolCallbackIndex index = config.toolCallbackIndex(context);
        assertThat(index.callbackFor("code_search")).isNotNull();
        assertThat(index.callbackFor("navigation_map")).isNotNull();
    }

    @Test
    void buildsEverySpecialistChatClientFromItsPrompt() {
        PromptLoader prompts = new PromptLoader();
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient built = mock(ChatClient.class);
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(built);

        assertThat(config.qnaChatClient(builder, prompts)).isSameAs(built);
        assertThat(config.directorChatClient(builder, prompts)).isSameAs(built);
        assertThat(config.refactorAnalyzeChatClient(builder, prompts)).isSameAs(built);
        assertThat(config.refactorApplyChatClient(builder, prompts)).isSameAs(built);
        assertThat(config.changeAnalyzerChatClient(builder, prompts)).isSameAs(built);
        assertThat(config.changelogEngineerChatClient(builder, prompts)).isSameAs(built);
        assertThat(config.changelogPlainChatClient(builder, prompts)).isSameAs(built);

        verify(builder, times(7)).defaultSystem(anyString());
        verify(builder, times(7)).build();
    }

    @Component
    static class BadTool {
        @Tool(description = "missing safety annotation")
        public String danger(String input) {
            return input;
        }
    }
}
