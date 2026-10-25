package com.codemind.config;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring AI 1.0.0's ChromaVectorStoreAutoConfiguration wires only collectionName into the
// builder and silently drops spring.ai.vectorstore.chroma.tenant-name / database-name, so the
// store always queries the SpringAiTenant/SpringAiDatabase defaults. We override the
// @ConditionalOnMissingBean autoconfig bean to apply all three from configuration.
@Configuration
@ConditionalOnProperty(name = "codemind.vector-store", havingValue = "chroma", matchIfMissing = true)
public class VectorStoreConfig {

    @Bean
    public ChromaVectorStore vectorStore(
            ChromaApi chromaApi,
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.vectorstore.chroma.collection-name:codemind}") String collectionName,
            @Value("${spring.ai.vectorstore.chroma.tenant-name:default_tenant}") String tenantName,
            @Value("${spring.ai.vectorstore.chroma.database-name:default_database}") String databaseName,
            @Value("${spring.ai.vectorstore.chroma.initialize-schema:true}") boolean initializeSchema) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName)
                .tenantName(tenantName)
                .databaseName(databaseName)
                .initializeSchema(initializeSchema)
                .build();
    }
}
