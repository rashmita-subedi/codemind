package com.codemind.agent.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
class AgentExecutorIntegrationTest {

    @Autowired
    private AgentExecutor executor;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void liveToolCallingLoopReturnsCitedAnswer() {
        ChatClient client = chatClientBuilder.build();

        AgentResult result = executor.execute(client,
                new AgentContext("it-1", "Use the navigation_map tool to list the indexed APIs, then summarise them."));

        assertThat(result.answer()).isNotBlank();
        assertThat(result.hitMaxSteps()).isFalse();
        assertThat(result.steps()).anyMatch(step -> step.action().equals("navigation_map"));
    }
}
