package com.codemind.retrieval;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingWarmup {

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public ApplicationRunner embeddingWarmupRunner(EmbeddingModel embeddingModel) {
        return args -> {
            try {
                embeddingModel.embed("warmup");
            } catch (Exception e) {
                System.out.println("WARN: embedding warmup skipped: " + e.getMessage());
            }
        };
    }
}
