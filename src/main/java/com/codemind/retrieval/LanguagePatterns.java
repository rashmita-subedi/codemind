package com.codemind.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Language- and framework-agnostic classification patterns. All defaults can be
 * overridden via {@code codemind.patterns.*} in application.yaml. Order matters
 * for {@code kindKeywords}: the first kind whose keywords match a chunk wins.
 */
@Component
@ConfigurationProperties("codemind.patterns")
public class LanguagePatterns {

    private List<String> codeFileExtensions = new ArrayList<>(List.of(
            ".java", ".kt", ".groovy", ".scala",
            ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs",
            ".py", ".rb", ".php",
            ".go", ".rs",
            ".cs", ".fs", ".vb",
            ".cpp", ".cc", ".c", ".h", ".hpp",
            ".sql", ".graphql", ".gql", ".proto",
            ".yaml", ".yml", ".properties",
            ".tf", ".hcl"
    ));

    private List<String> ignoredPathFragments = new ArrayList<>(List.of(
            "/.git/", "/.idea/", "/.vscode/", "/.gradle/",
            "/target/", "/build/", "/dist/", "/out/", "/bin/", "/obj/",
            "/node_modules/", "/vendor/", "/__pycache__/", "/.venv/", "/venv/",
            "/coverage/", "/.next/", "/.nuxt/",
            "/test/", "/tests/", "/__tests__/", "/spec/",
            "/generated/", "/generated-sources/",
            "/static/", "/public/", "/assets/", "/.mvn/"
    ));

    private List<String> contractFileSuffixes = new ArrayList<>(List.of(
            ".proto", ".graphql", ".gql", ".openapi.yaml", ".openapi.yml",
            ".swagger.yaml", ".swagger.yml", ".raml"
    ));

    private long maxFileSizeBytes = 1_000_000L;

    /**
     * Ordered map of kind → keyword list. First match wins during classification
     * and the same keyword lists are used as content-match boosters during
     * intent-aware re-ranking.
     */
    private Map<String, List<String>> kindKeywords = new LinkedHashMap<>();

    private List<String> navigationMarkers = new ArrayList<>(List.of(
            // generic structural markers across most C-family / JVM / dynamic langs
            "class ", "interface ", "struct ", "trait ", "enum ",
            "function ", "func ", "fn ", "def ", "sub ",
            "public ", "private ", "protected ", "internal ",
            "export ", "module.exports", "module ",
            // common identifiers worth surfacing
            "controller", "router", "route", "handler", "service",
            "repository", "client", "producer", "consumer",
            "base-url", "baseurl", "url:", "endpoint", "topic"
    ));

    public LanguagePatterns() {
        kindKeywords.put("integration", new ArrayList<>(List.of(
                // Spring
                "@feignclient", "resttemplate", "webclient", "kafkatemplate",
                "@kafkalistener", "rabbittemplate", "exchange(",
                // generic JVM / language-agnostic
                "httpclient", "httpsession", "okhttp",
                // JavaScript / TypeScript
                "fetch(", "axios.", "got.", "node-fetch",
                // Python
                "requests.get", "requests.post", "httpx.", "aiohttp",
                "boto3", "kafka.consumer", "kafka.producer",
                // Go
                "http.get", "http.post", "http.do", "http.newrequest",
                // Ruby
                "httparty", "rest-client", "faraday",
                // .NET
                "httpclient.", "httpclientfactory",
                // PHP
                "guzzlehttp", "curl_exec",
                // messaging / events (generic)
                ".publish(", ".subscribe(", ".consume(", ".produce("
        )));

        kindKeywords.put("api", new ArrayList<>(List.of(
                // Spring
                "@restcontroller", "@controller",
                "@requestmapping", "@getmapping", "@postmapping",
                "@putmapping", "@deletemapping", "@patchmapping",
                // NestJS / Angular controllers
                "@injectable", "@get(", "@post(", "@put(", "@delete(", "@patch(",
                // Express / Koa / generic Node routers
                "app.get(", "app.post(", "app.put(", "app.delete(",
                "router.get(", "router.post(", "router.put(", "router.delete(",
                // Flask / FastAPI / Sanic / Django REST
                "@app.route", "@app.get", "@app.post",
                "@router.get", "@router.post", "@router.put", "@router.delete",
                "@api_view", "as_view(",
                // Go web frameworks
                ".handlefunc(", ".handle(", "gin.h{", "gin.context",
                "echo.context", "fiber.ctx",
                // Rails / Sinatra
                "get '/", "post '/", "put '/", "delete '/", "resources :",
                // ASP.NET
                "[httpget", "[httppost", "[httpput", "[httpdelete", "[route(",
                // Rust web
                "actix_web::", "warp::path", "rocket::get", "rocket::post"
        )));

        kindKeywords.put("service", new ArrayList<>(List.of(
                "@service", "@injectable", "@component",
                "usecase", "interactor",
                "businesslogic", "businessrule"
        )));

        kindKeywords.put("data", new ArrayList<>(List.of(
                // Spring / JPA / Hibernate
                "@repository", "@entity", "@table", "jparepository", "crudrepository",
                "dao",
                // Python ORMs
                "session.query", "session.add", "session.commit",
                "queryset.", "objects.filter", "objects.create",
                // Node ORMs
                "knex(", "prisma.", "typeorm", "sequelize.", "mongoose.",
                // Go ORMs
                "gorm.", "sqlx.", "ent.",
                // Ruby / Rails
                "activerecord", "has_many", "belongs_to",
                // .NET / EF
                "dbcontext", "dbset", "linq.where",
                // PHP / Laravel
                "eloquent",
                // raw SQL hints
                "select ", "insert into", "update ", "delete from"
        )));

        kindKeywords.put("contract", new ArrayList<>(List.of(
                "openapi", "swagger", "asyncapi", "graphql schema",
                "syntax = \"proto", "service ", "rpc "
        )));
    }

    public List<String> getCodeFileExtensions() {
        return codeFileExtensions;
    }

    public void setCodeFileExtensions(List<String> codeFileExtensions) {
        this.codeFileExtensions = codeFileExtensions;
    }

    public List<String> getIgnoredPathFragments() {
        return ignoredPathFragments;
    }

    public void setIgnoredPathFragments(List<String> ignoredPathFragments) {
        this.ignoredPathFragments = ignoredPathFragments;
    }

    public List<String> getContractFileSuffixes() {
        return contractFileSuffixes;
    }

    public void setContractFileSuffixes(List<String> contractFileSuffixes) {
        this.contractFileSuffixes = contractFileSuffixes;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public Map<String, List<String>> getKindKeywords() {
        return kindKeywords;
    }

    public void setKindKeywords(Map<String, List<String>> kindKeywords) {
        this.kindKeywords = kindKeywords;
    }

    public List<String> getNavigationMarkers() {
        return navigationMarkers;
    }

    public void setNavigationMarkers(List<String> navigationMarkers) {
        this.navigationMarkers = navigationMarkers;
    }

    public boolean isCodeFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return codeFileExtensions.stream().anyMatch(lower::endsWith);
    }

    public boolean isIgnoredPath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return ignoredPathFragments.stream().anyMatch(normalized::contains);
    }

    public boolean isContractFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return contractFileSuffixes.stream().anyMatch(lower::endsWith);
    }

    public String classify(String pathAndContent) {
        String lower = pathAndContent.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : kindKeywords.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return "code";
    }

    public List<String> keywordsFor(String kind) {
        return kindKeywords.getOrDefault(kind, List.of());
    }

    public boolean isNavigationLine(String lowerLine) {
        for (String marker : navigationMarkers) {
            if (lowerLine.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
