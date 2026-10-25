package com.codemind.cli;

import java.util.List;

public record ParsedCommand(String name, List<String> args) {
    public String firstArgOr(String fallback) {
        return args.isEmpty() ? fallback : args.getFirst();
    }
}
