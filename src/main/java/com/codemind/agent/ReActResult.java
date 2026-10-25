package com.codemind.agent;

import java.util.List;

public record ReActResult(String answer, List<ReActStep> steps) {
}
