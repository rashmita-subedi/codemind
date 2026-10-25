package com.codemind.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLoaderTest {

    private final PromptLoader loader = new PromptLoader();

    @Test
    void loadsExistingPrompt() {
        String content = loader.load("prompts/qna-system.st");

        assertThat(content).contains("CodeMind");
        assertThat(content).contains("<<UNTRUSTED>>");
    }

    @Test
    void throwsWhenPromptMissing() {
        assertThatThrownBy(() -> loader.load("prompts/does-not-exist.st"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing prompt resource");
    }
}
