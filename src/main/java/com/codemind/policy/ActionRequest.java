package com.codemind.policy;

public record ActionRequest(String action, SafetyLevel safetyLevel, String preview) {
}
