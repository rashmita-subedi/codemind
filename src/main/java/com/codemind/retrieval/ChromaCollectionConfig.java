package com.codemind.retrieval;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "codemind.vector-store", havingValue = "chroma", matchIfMissing = true)
public class ChromaCollectionConfig {

    @Bean
    public static BeanPostProcessor chromaCollectionInitializer(Environment env) {
        return new ChromaCollectionInitializer(env);
    }

    private static final class ChromaCollectionInitializer implements BeanPostProcessor {

        private final Environment env;
        private boolean done;

        private ChromaCollectionInitializer(Environment env) {
            this.env = env;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (!done && "vectorStore".equals(beanName)) {
                ensureCollection();
                done = true;
            }
            return bean;
        }

        private void ensureCollection() {
            String host = env.getProperty("spring.ai.vectorstore.chroma.client.host", "http://localhost");
            String port = env.getProperty("spring.ai.vectorstore.chroma.client.port", "8000");
            String collection = env.getProperty("spring.ai.vectorstore.chroma.collection-name", "codemind");
            String tenant = env.getProperty("spring.ai.vectorstore.chroma.tenant-name", "default_tenant");
            String database = env.getProperty("spring.ai.vectorstore.chroma.database-name", "default_database");

            String baseUrl = host + ":" + port;
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            createIgnoringConflict(client,
                    baseUrl + "/api/v2/tenants",
                    "{\"name\":\"" + escape(tenant) + "\"}",
                    "tenant '" + tenant + "'");

            createIgnoringConflict(client,
                    baseUrl + "/api/v2/tenants/" + tenant + "/databases",
                    "{\"name\":\"" + escape(database) + "\"}",
                    "database '" + database + "'");

            createIgnoringConflict(client,
                    baseUrl + "/api/v2/tenants/" + tenant + "/databases/" + database + "/collections",
                    "{\"name\":\"" + escape(collection) + "\",\"get_or_create\":true}",
                    "collection '" + collection + "'");
        }

        private void createIgnoringConflict(HttpClient client, String uri, String body, String label) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(uri))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return;
                }
                String responseBody = response.body() == null ? "" : response.body();
                String lower = responseBody.toLowerCase();
                if (status == 409
                        || lower.contains("already exists")
                        || lower.contains("uniqueconstraint")
                        || lower.contains("already_exists")) {
                    return;
                }
                System.out.println("WARN: failed to ensure Chroma " + label + " at " + uri
                        + " (status " + status + "): " + responseBody);
            } catch (Exception e) {
                System.out.println("WARN: failed to ensure Chroma " + label + " at " + uri + ": " + e.getMessage());
            }
        }

        private String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
