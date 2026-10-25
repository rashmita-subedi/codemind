package com.codemind.config;

import com.codemind.agent.core.ToolCallbackIndex;
import com.codemind.agent.core.ToolSafetyRegistry;
import com.codemind.policy.SafeTool;
import com.codemind.policy.SafetyLevel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class AgentConfig {

    // One ChatClient per specialist, each carrying its own system prompt. The AgentExecutor
    // overrides the tool callbacks at call time, so these beans only bake in the .st persona.
    @Bean
    ChatClient qnaChatClient(ChatClient.Builder builder, PromptLoader prompts) {
        return builder.defaultSystem(prompts.load("prompts/qna-system.st")).build();
    }

    @Bean
    ChatClient directorChatClient(ChatClient.Builder builder, PromptLoader prompts) {
        return builder.defaultSystem(prompts.load("prompts/director-system.st")).build();
    }

    @Bean
    ChatClient refactorAnalyzeChatClient(ChatClient.Builder builder, PromptLoader prompts) {
        return builder.defaultSystem(prompts.load("prompts/refactor-analyze-system.st")).build();
    }

    @Bean
    ChatClient refactorApplyChatClient(ChatClient.Builder builder, PromptLoader prompts) {
        return builder.defaultSystem(prompts.load("prompts/refactor-apply-system.st")).build();
    }

    @Bean
    ChatClient changeAnalyzerChatClient(ChatClient.Builder builder, PromptLoader prompts) {
        return builder.defaultSystem(prompts.load("prompts/change-analyzer-system.st")).build();
    }

    @Bean
    ChatClient changelogEngineerChatClient(ChatClient.Builder builder, PromptLoader prompts) {
        return builder.defaultSystem(prompts.load("prompts/changelog-engineer-system.st")).build();
    }

    @Bean
    ChatClient changelogPlainChatClient(ChatClient.Builder builder, PromptLoader prompts) {
        return builder.defaultSystem(prompts.load("prompts/changelog-plain-system.st")).build();
    }

    @Bean
    ToolSafetyRegistry toolSafetyRegistry(ApplicationContext context) {
        Map<String, SafetyLevel> levels = new HashMap<>();
        for (Object bean : toolBeans(context)) {
            Class<?> target = AopProxyUtils.ultimateTargetClass(bean);
            for (Method method : ReflectionUtils.getDeclaredMethods(target)) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                SafeTool safeTool = AnnotatedElementUtils.findMergedAnnotation(method, SafeTool.class);
                if (safeTool == null) {
                    throw new IllegalStateException("@Tool method " + target.getName() + "#"
                            + method.getName() + " is missing @SafeTool");
                }
                levels.put(ToolUtils.getToolName(method), safeTool.value());
            }
        }
        return new ToolSafetyRegistry(levels);
    }

    @Bean
    ToolCallbackIndex toolCallbackIndex(ApplicationContext context) {
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolBeans(context).toArray())
                .build()
                .getToolCallbacks();
        Map<String, ToolCallback> byName = new HashMap<>();
        for (ToolCallback callback : toolCallbacks) {
            byName.put(callback.getToolDefinition().name(), callback);
        }
        return new ToolCallbackIndex(byName);
    }

    // Iterates bean definitions and resolves types without forcing instantiation, so beans
    // with no @Tool methods (e.g. the eager-probing Ollama embedding model) are never created
    // just to run this scan.
    private List<Object> toolBeans(ApplicationContext context) {
        List<Object> beans = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null && hasToolMethod(type)) {
                beans.add(context.getBean(name));
            }
        }
        return beans;
    }

    private boolean hasToolMethod(Class<?> type) {
        // Resolve the user class behind any CGLIB proxy: proxy method overrides do not carry
        // the method-level @Tool annotation, so scanning the proxy type would skip a proxied tool.
        for (Method method : ReflectionUtils.getDeclaredMethods(ClassUtils.getUserClass(type))) {
            if (method.isAnnotationPresent(Tool.class)) {
                return true;
            }
        }
        return false;
    }
}
