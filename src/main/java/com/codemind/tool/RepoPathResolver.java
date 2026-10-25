package com.codemind.tool;

import com.codemind.service.IndexService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Translates the LLM-supplied {@code repoPath} parameter into a real filesystem path.
 *
 * <p>The agents have no idea which workspace the user actually indexed, so they default
 * to {@code "."}. When that happens we substitute the path the user last ran {@code index}
 * against (kept by {@link IndexService#currentRoot()}). When the user never indexed
 * anything we fall back to the JVM's working directory.</p>
 */
@Component
public class RepoPathResolver {

    private final IndexService indexService;

    public RepoPathResolver(IndexService indexService) {
        this.indexService = indexService;
    }

    public String resolve(String suppliedRepoPath) {
        String supplied = suppliedRepoPath == null ? "" : suppliedRepoPath.trim();
        if (!supplied.isEmpty() && !".".equals(supplied) && !"./".equals(supplied)) {
            return supplied;
        }
        Path active = indexService.currentRoot();
        if (active != null) {
            return active.toString();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
    }
}
