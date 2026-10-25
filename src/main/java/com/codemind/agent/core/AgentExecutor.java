package com.codemind.agent.core;

import com.codemind.memory.ConversationMemoryService;
import com.codemind.policy.ActionRequest;
import com.codemind.policy.SafetyLevel;
import com.codemind.service.ApprovalService;
import com.codemind.service.AuditService;
import com.codemind.service.PromptGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AgentExecutor {

    private static final String REFLECTION_NUDGE =
            "Reflect briefly: do you already have enough evidence to give the final, cited answer now, "
            + "or should you call exactly one more tool? If you have enough, answer now.";

    private static final java.util.Set<String> SYNTHETIC_ACTIONS =
            java.util.Set.of("reflect", "model_error", "model_empty");

    private final PromptGuard guard;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final ToolSafetyRegistry safety;
    private final ToolCallbackIndex callbacks;
    private final ConversationMemoryService memory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentExecutor(PromptGuard guard, ApprovalService approvals, AuditService audit,
                         ToolSafetyRegistry safety, ToolCallbackIndex callbacks,
                         ConversationMemoryService memory) {
        this.guard = guard;
        this.approvals = approvals;
        this.audit = audit;
        this.safety = safety;
        this.callbacks = callbacks;
        this.memory = memory;
    }

    public AgentResult execute(ChatClient chatClient, AgentContext context) {
        long start = System.nanoTime();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(callbacks.all())
                .internalToolExecutionEnabled(false)
                .build();

        // Seed the loop with this session's recent turns so the agent retains context
        // across calls instead of starting cold on every invocation.
        List<Message> messages = new ArrayList<>(memory.messages(context.sessionId()));
        messages.add(new UserMessage(context.originalInput()));
        int modelTurns = 0;

        while (!context.finished() && modelTurns < context.maxSteps()) {
            if (modelTurns > 0 && modelTurns % 3 == 0) {
                messages.add(new UserMessage(REFLECTION_NUDGE));
                context.addStep(new ReActStep(context.steps().size() + 1, "", "reflect", "",
                        "self-reflection injected", SafetyLevel.SAFE, true, 0L));
            }

            long stepStart = System.nanoTime();
            int index = context.steps().size() + 1;
            modelTurns++;

            ChatResponse response;
            try {
                response = chatClient.prompt()
                        .messages(messages)
                        .options(options)
                        .call()
                        .chatResponse();
            } catch (Exception e) {
                audit.record("llm.error", e.getClass().getSimpleName());
                context.markFinished(degraded("The model endpoint failed: " + e.getMessage(), context));
                context.addStep(new ReActStep(index, "", "model_error", "", String.valueOf(e.getMessage()),
                        SafetyLevel.SAFE, false, ms(stepStart)));
                break;
            }

            if (response == null || response.getResults() == null || response.getResults().isEmpty()
                    || response.getResult().getOutput() == null) {
                context.markFinished("The model returned an empty response.");
                context.addStep(new ReActStep(index, "", "model_empty", "", "null/empty response",
                        SafetyLevel.SAFE, false, ms(stepStart)));
                break;
            }

            AssistantMessage assistant = response.getResult().getOutput();
            String thought = assistant.getText() == null ? "" : assistant.getText();

            if (assistant.hasToolCalls()) {
                messages.add(assistant);
                List<ToolResponse> responses = new ArrayList<>();
                for (ToolCall call : assistant.getToolCalls()) {
                    gateAndRun(context, call, thought, responses, stepStart);
                }
                messages.add(new ToolResponseMessage(responses));
                continue;
            }

            ToolDirective directive = tryParseTextDirective(thought);
            if (directive != null) {
                messages.add(assistant);
                List<ToolResponse> responses = new ArrayList<>();
                ToolCall synthetic = new ToolCall("fallback-" + index, "function",
                        directive.name(), directive.argsJson());
                gateAndRun(context, synthetic, thought, responses, stepStart);
                // Feed the result back as plain text, NOT a ToolResponseMessage: the synthetic
                // tool_call_id was never declared in a real assistant tool_calls, so an OpenAI-
                // compatible gateway rejects an orphaned tool message on the next turn.
                messages.add(new UserMessage("Observation (" + directive.name() + "): "
                        + responses.get(0).responseData()));
                continue;
            }

            context.markFinished(thought);
            context.addStep(new ReActStep(index, thought, "respond", "", thought,
                    SafetyLevel.SAFE, true, ms(stepStart)));
            break;
        }

        boolean hitMaxSteps = !context.finished() && modelTurns >= context.maxSteps();
        if (hitMaxSteps) {
            audit.record("agent.maxsteps", context.sessionId());
            context.markFinished("Reached the " + context.maxSteps()
                    + "-step limit. Best-effort: " + lastObservation(context));
        }
        memory.recordExchange(context.sessionId(), context.originalInput(), context.finalText());
        return AgentResult.of(context, ms(start), hitMaxSteps);
    }

    private void gateAndRun(AgentContext context, ToolCall call, String thought,
                            List<ToolResponse> out, long stepStart) {
        int index = context.steps().size() + 1;
        String action = call.id().startsWith("fallback-") ? "fallback_parse" : call.name();
        String safeArgs = guard.sanitize(call.arguments());

        if (!safety.contains(call.name())) {
            audit.record("tool.unknown", call.name());
            String observation = "Unknown tool: " + call.name();
            out.add(new ToolResponse(call.id(), call.name(), observation));
            context.addStep(new ReActStep(index, thought, action, safeArgs, observation,
                    SafetyLevel.SAFE, false, ms(stepStart)));
            return;
        }

        SafetyLevel level = safety.levelFor(call.name());
        ActionRequest request = new ActionRequest(call.name() + ":" + context.sessionId(), level, safeArgs);
        if (!approvals.approved(request)) {
            audit.record(call.name() + ".rejected", safeArgs);
            String observation = "User declined " + call.name();
            out.add(new ToolResponse(call.id(), call.name(), observation));
            context.addStep(new ReActStep(index, thought, action, safeArgs, observation,
                    level, false, ms(stepStart)));
            return;
        }

        String raw;
        // The executor is the single approval authority for the agent loop: it has already
        // approved above, so suppress any second prompt from a tool that self-gates.
        approvals.enterPreApproved();
        try {
            raw = callbacks.callbackFor(call.name()).call(safeArgs);
        } catch (Exception e) {
            audit.record(call.name() + ".error", e.getClass().getSimpleName());
            String observation = guard.sanitize("Tool " + call.name() + " failed: " + e.getMessage());
            out.add(new ToolResponse(call.id(), call.name(), observation));
            context.addStep(new ReActStep(index, thought, action, safeArgs, observation,
                    level, false, ms(stepStart)));
            return;
        } finally {
            approvals.exitPreApproved();
        }
        audit.record(call.name() + ".applied", safeArgs);
        String observation = guard.sanitize(raw);
        out.add(new ToolResponse(call.id(), call.name(), observation));
        context.addStep(new ReActStep(index, thought, action, safeArgs, observation,
                level, true, ms(stepStart)));
    }

    private ToolDirective tryParseTextDirective(String text) {
        if (text == null || !text.contains("\"tool\"")) {
            return null;
        }
        String json = text.trim();
        if (json.contains("```")) {
            int first = json.indexOf("```");
            int last = json.lastIndexOf("```");
            if (last > first) {
                json = json.substring(first + 3, last);
                int newline = json.indexOf('\n');
                if (newline >= 0 && json.substring(0, newline).toLowerCase().contains("json")) {
                    json = json.substring(newline + 1);
                }
            }
        }
        try {
            JsonNode node = objectMapper.readTree(json.trim());
            if (node == null || !node.hasNonNull("tool")) {
                return null;
            }
            JsonNode args = node.get("args");
            String argsJson;
            if (args == null) {
                argsJson = "{}";
            } else if (args.isTextual()) {
                argsJson = args.asText();
            } else {
                argsJson = args.toString();
            }
            return new ToolDirective(node.get("tool").asText(), argsJson);
        } catch (Exception e) {
            return null;
        }
    }

    private String degraded(String message, AgentContext context) {
        String evidence = context.steps().stream()
                .filter(step -> step.approved() && step.observation() != null && !step.observation().isBlank())
                .map(step -> "- " + step.action() + ": " + truncate(step.observation()))
                .collect(Collectors.joining("\n"));
        return evidence.isBlank() ? message : message + "\n\nEvidence gathered before failure:\n" + evidence;
    }

    private String lastObservation(AgentContext context) {
        List<ReActStep> steps = context.steps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            ReActStep step = steps.get(i);
            if (SYNTHETIC_ACTIONS.contains(step.action())) {
                continue;
            }
            if (step.observation() != null && !step.observation().isBlank()) {
                return step.observation();
            }
        }
        return "no observations recorded";
    }

    private String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    private long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record ToolDirective(String name, String argsJson) {
    }
}
