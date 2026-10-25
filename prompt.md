# CodeMind — Score Recovery Prompts

Each prompt is **self-contained** — paste it whole into Claude Code as one message.
**Run them in order.** Lower numbers are additive / low-blast-radius; higher numbers touch existing behaviour.

Risk ladder:

| # | Prompt | Risk | Why this order |
|---|---|---|---|
| 1 | Persist the audit log to disk | **None** — pure addition | `AuditService` only logs to stdout today; persistence is invisible to every existing caller |
| 2 | Implement `RollbackCommand` for real | **None** — touches a placeholder | The command is a hardcoded `println` today; implementing it cannot regress anything |
| 3 | Add `ConversationMemoryService` (in-memory ring buffer) — **wire-up not yet** | **Very low** — new class only | New service used by no one yet; safe to land |
| 4 | Add `run_tests` and `find_symbol` tools | **Low** — new tool beans | Tool callback index is rebuilt at startup; adding tools cannot break existing ones |
| 5 | Make `PromptGuard.sanitize` actually wrap untrusted spans | **Low-medium** — touches every LLM call | All call sites already invoke `sanitize(...)`; we change the *body*, not the wiring. Existing prompts already end with the cooperating paragraph |
| 6 | Persist + reuse `ConversationMemoryService` across turns in the REPL | **Medium** — modifies `AgentExecutor` + specialists | Changes how the executor builds its message list — needs careful threading of `sessionId` |
| 7 | Add `LongTermMemoryService` backed by Chroma (separate collection) | **Medium** — new Chroma collection, new bean | New collection is isolated from the code-chunk one; no collision, but adds a startup dependency |
| 8 | Replace `ask` mode's `ReActEngine` with the agentic `QnAAgent` | **High** — changes the answer path most users see | This is *the* feature change. Must be feature-flagged and A/B-tested before flipping the default |
| 9 | Tighten `agentic` mode: still prompt once for `CONFIRM_REQUIRED` tools, but with a "remember for this session" option | **High** — changes documented agentic-mode contract | The current docs and tests assume agentic = silent. We have to update tests + docs + UX |
| 10 | Add an offline-chat fallback (Ollama chat model) when `ai-hub.f1arl.com` is unreachable | **High** — adds a new LLM provider path | Requires a second `ChatClient` builder + routing logic; can confuse retries; needs prompts re-tested against a weaker local model |

---

## Prompt 1 — Persist the audit log to disk (No risk)

**Goal:** Score-recovery for *System Design (audit trail)* and *Privacy (provable trail of mutating actions)*.

**Files to change:**
- `src/main/java/com/codemind/service/AuditService.java` — extend, do not break the signature
- `src/main/resources/application.yaml` — add one config block

**What to change, precisely:**

1. Open `AuditService`. Today it only does `System.out.printf("[AUDIT] %s action=%s detail=%s%n", Instant.now(), action, detail);`.
   Keep the stdout line **unchanged** (other code/tests may grep for it). Add, in the same method, a write to a JSON-Lines file at `.codemind/audit/audit.log` under the **active workspace root** (use `com.codemind.cli.GitCommitter.activeRoot()` if it exposes the root; otherwise inject `IndexService` and call `currentRoot()` — if null, fall back to `System.getProperty("user.dir")`).
2. Each JSONL record must have: `ts` (ISO-8601 UTC), `action`, `detail`, `pid` (`ProcessHandle.current().pid()`), `user` (`System.getProperty("user.name")`).
3. File writes must be **append-only**, UTF-8, with `StandardOpenOption.CREATE, StandardOpenOption.APPEND`. Create the parent dir if missing.
4. **Do not** throw out of `record(...)` if the write fails — degrade silently to stdout (we already have it). Log one warning to stderr on the *first* failure per process and never again (use an `AtomicBoolean` guard).
5. In `application.yaml`, add:
   ```yaml
   codemind:
     audit:
       file-enabled: true
       directory: .codemind/audit
   ```
   Read those into `AuditService` with `@Value` defaults that mirror them. `file-enabled: false` must short-circuit the write but keep stdout.
6. Write a `AuditServiceTest` with positive (writes a line containing the action) and negative (write fails → still returns, stdout still printed) cases. Use `@TempDir`. **Maintain 100% line + branch coverage.**

**Do not:** rename the existing `record(action, detail)` method, change its parameter order, or add throwing. Multiple call sites depend on the current contract.

---

## Prompt 2 — Implement `RollbackCommand` for real (No risk; placeholder today)

**Goal:** Score-recovery for *System Design (no half-finished features)* and *Autonomy (multi-step: refactor → review → rollback)*.

**Files to change:**
- `src/main/java/com/codemind/cli/command/RollbackCommand.java` — replace the placeholder body
- new: `src/main/java/com/codemind/service/RollbackService.java`
- `src/main/java/com/codemind/tool/WriteFileTool.java` — change *one line* only

**What to change, precisely:**

1. `WriteFileTool` writes backups as `<filename>.<nanoTime>.bak` into `.codemind/backup/`. That filename loses the original path so multiple files with the same name collide. Change the backup naming to encode the **relative path** with `/` → `__`, e.g. `src/main/java/Foo.java` → `src__main__java__Foo.java.<nanoTime>.bak`. Keep the `.codemind/backup/` directory location.
2. Create `RollbackService` with one public method: `List<RestoredFile> rollbackLatest(int n)` — restores the `n` most-recent backups (`n=0` → all from the latest single refactor session, detected by clustering backups whose `nanoTime` is within 5 seconds of the newest).
3. For each restore: read the `.bak` file, write it atomically over the original path (use the same atomic-move + parent-mkdirs trick already in `WriteFileTool.atomicWrite(...)` — refactor that into a tiny shared `AtomicFileWriter` utility under `com.codemind.service.io`, and call it from both places).
4. Route through `ApprovalService` (`CONFIRM_REQUIRED` equivalent): one y/N for the whole batch with a preview listing each file that will be restored.
5. `RollbackCommand` becomes:
   - `@Parameters(arity = "0..1") int howMany = 0;`
   - Calls `rollbackService.rollbackLatest(howMany)`, prints a table of restored files, calls `audit.record("rollback.applied", "<count> files")`.
6. Tests: `RollbackServiceTest` (positive: restores file content; negative: no backups → returns empty list with a clear message; negative: user declines approval). `@TempDir`-based.

**Do not:** delete the `.bak` files after restore — keep them so a rollback itself can be undone.

---

## Prompt 3 — `ConversationMemoryService` (in-memory ring buffer; new class only) (Very low risk)

**Goal:** Score-recovery for *Memory Effectiveness (context retention)*. This prompt **only adds the class** — no callers are changed yet. Prompt 6 wires it in.

**Files to add:**
- `src/main/java/com/codemind/memory/ConversationMemoryService.java`
- `src/main/java/com/codemind/memory/ConversationTurn.java` (record)

**What to build, precisely:**

1. `ConversationTurn` = `record ConversationTurn(Instant at, String role, String content, String toolName)` — `toolName` nullable.
2. `ConversationMemoryService` is `@Service`. State: `Map<String /*sessionId*/, Deque<ConversationTurn>>`. Use `ConcurrentHashMap` + `ArrayDeque` synchronised via `synchronized` blocks on each session's deque.
3. Public API (exactly):
   - `void append(String sessionId, ConversationTurn turn)`
   - `List<ConversationTurn> recent(String sessionId, int max)` — returns newest-last
   - `void clear(String sessionId)`
   - `int sessionCount()` — for diagnostics
4. Cap each session at `codemind.memory.conversation.max-turns` (default **20**). Evict oldest beyond the cap.
5. **Sanitize on the way in:** inject `PromptGuard` and call `guard.sanitize(turn.content())` before storing. (This is the same direction CLAUDE.md requires for `LongTermMemoryService`.)
6. Tests: append → recent returns in order; cap eviction; clear; concurrent appends from 100 virtual threads must not throw. **100% coverage.**

**Do not:** make this a singleton with static methods. Spring-managed, constructor-injected only.
**Do not:** wire it into `AgentExecutor` or any specialist yet — that's Prompt 6.

---

## Prompt 4 — Add `run_tests` and `find_symbol` tools (Low risk; new tool beans)

**Goal:** Score-recovery for *Tool Integration (effective utilization)* and *Agent Intelligence (problem solving)*.

**Files to add:**
- `src/main/java/com/codemind/tool/RunTestsTool.java`
- `src/main/java/com/codemind/tool/FindSymbolTool.java`

**`RunTestsTool` requirements:**

- `@Tool(name = "run_tests")` `@SafeTool(SafetyLevel.CONFIRM_REQUIRED)` (tests can have side effects).
- Parameters: `String repoPath` (use `RepoPathResolver`), `String testFilter` (optional Maven `-Dtest=` pattern, empty = all).
- Detect build tool: `pom.xml` → `./mvnw test` (Windows: `mvnw.cmd test`), `build.gradle` → `./gradlew test`, `package.json` → `npm test`. Returns "no recognised build file" otherwise.
- Run with `ProcessBuilder`, 5-minute timeout (`TimeUnit.MINUTES, 5`), redirect stderr → stdout.
- Return last 200 lines of output + a summary line (`PASS|FAIL <duration>`).
- Route the *execution* through `ApprovalService` first with a preview of the exact command.
- `audit.record("run_tests.applied"|"run_tests.rejected"|"run_tests.failed", ...)`.

**`FindSymbolTool` requirements:**

- `@Tool(name = "find_symbol")` `@SafeTool(SafetyLevel.SAFE)`.
- Parameters: `String symbol`, `String kindHint` (one of `class`, `method`, `function`, `any`).
- Scans `ChunkStore.cache` (add a package-private `Collection<CodeChunk> all()` accessor to `ChunkStore` interface and both implementations) plus a regex fall-back using `AstAnalyzerTool`'s patterns.
- Returns `<file>:<line> <kind> <name>` lines, capped at 30 results.

**Do not:** rebuild the tool callback index manually — `AgentConfig.toolCallbackIndex(...)` picks up `@Tool` methods at startup automatically. The new tools will appear without any wiring.

**Do not:** call `Files.write` or write any state — both tools are read-only / side-effect-via-approval only.

Tests: 100% coverage. For `RunTestsTool`, mock `ProcessBuilder` behaviour by extracting the process invocation into a private method and using a test-only subclass (or split out a `ProcessRunner` collaborator). For approval: cover both approved and declined branches.

---

## Prompt 5 — Make `PromptGuard.sanitize` actually wrap untrusted spans (Low-medium risk)

**Goal:** Score-recovery for *Agent Intelligence (defence against prompt injection)* and *Privacy (no exfil via untrusted content)*. Today `PromptGuard.sanitize(input)` is a literal pass-through.

**Files to change:**
- `src/main/java/com/codemind/service/PromptGuard.java`
- (verify only — no edit) Every `.st` prompt file already ends with the cooperating paragraph per CLAUDE.md. Confirm and add it to any that is missing.

**What to change, precisely:**

1. Replace the body of `sanitize(String input)`. New behaviour:
   - If `input` is null → `""`.
   - If `enabled` is false → return `input` unchanged (preserves the existing escape hatch).
   - Otherwise:
     - **Defang inner markers.** If the input contains either `<<UNTRUSTED>>` or `<<END_UNTRUSTED>>` literally, replace with `<<U_UNTRUSTED>>` / `<<U_END_UNTRUSTED>>` so a hostile input cannot break out of the wrapper.
     - **Strip obvious instruction-injection patterns** *only* — do not paraphrase. Detect the regexes below and wrap the matched span with `[BLOCKED:` … `]` (so the model still sees the attempt, but cannot follow it):
       - `(?i)ignore (all|previous|above) (instructions|prompts)`
       - `(?i)you are now`
       - `(?i)system:\s*`
       - `(?i)disregard the system prompt`
     - **Wrap** the resulting string in `<<UNTRUSTED>>\n` … `\n<<END_UNTRUSTED>>` and return.
2. Add a `String sanitizeRaw(String input)` overload that only does step 1 *without* the wrapping — needed by call sites that build composite prompts (e.g. `RefactorAgent.apply(...)` already wraps the approved plan itself; using `sanitize` there would double-wrap).
3. Update **only the call site** in `RefactorAgent.apply` that currently does its own `<<UNTRUSTED>>` wrap — switch it to `guard.sanitizeRaw(approvedPlan)` to avoid the double wrap.
4. Audit every other call site of `sanitize(...)`: `DirectorAgent.dispatch`, `QnAAgent.ask`, `RefactorAgent.analyze`, `ChangeAnalyzerAgent.generateChangelog`, `AgentExecutor.gateAndRun` (args + observation). Each of those expects the wrapping — no change needed.

**Do not:** add HTML escaping, do not lowercase, do not normalise whitespace, do not strip code fences. Those would corrupt code observations.
**Do not:** change the public method name `sanitize` or break the existing `(String) -> String` contract.

Tests: positive (untrusted content gets wrapped; injection patterns get `[BLOCKED:…]`); negative (null → empty, disabled → pass-through, inner `<<UNTRUSTED>>` gets defanged). `PromptGuardTest` must be at **100% line + branch**. Add an `AgentExecutorTest` regression: a tool observation that contains `<<END_UNTRUSTED>>` cannot break out of the wrapper into the next prompt.

---

## Prompt 6 — Thread `ConversationMemoryService` through the agent loop (Medium risk)

**Goal:** Score-recovery for *Memory Effectiveness (long-task continuity)* and *Agent Intelligence (follow-ups)*. This is where Prompt 3 starts mattering.

**Files to change:**
- `src/main/java/com/codemind/cli/CliRunner.java` — give each REPL invocation a stable `sessionId`
- `src/main/java/com/codemind/agent/QnAAgent.java`, `RefactorAgent.java`, `ChangeAnalyzerAgent.java`, `DirectorAgent.java` — accept (don't generate) the `sessionId`
- `src/main/java/com/codemind/agent/core/AgentExecutor.java` — prepend recent turns into the message list; append the final answer
- `src/main/java/com/codemind/service/QaService.java` — accept `sessionId`

**What to change, precisely:**

1. `CliRunner` currently runs the REPL with no concept of a session. Add a private final `String sessionId = UUID.randomUUID().toString();` field. On `clear`, regenerate it. Pass it into `qaService.ask(...)`, `refactorAgent.analyze(...)`, etc.
2. **Today** every specialist does `new AgentContext(UUID.randomUUID().toString(), input)` — that erases continuity. Change each `ask(String)` / `analyze(String)` / `apply(String, String)` / `generateChangelog(String, boolean)` etc. to take an *optional* `String sessionId` parameter (use method overloading; keep the old single-arg signatures delegating to `UUID.randomUUID().toString()` so the CLI subcommand entrypoints that don't have a session keep working).
3. In `AgentExecutor.execute(chatClient, context)`:
   - Inject `ConversationMemoryService memory`.
   - **Before** the loop, fetch `memory.recent(context.sessionId(), 6)`. For each turn, prepend a `UserMessage` (role `user`) or `AssistantMessage` (role `assistant`) ahead of the new `UserMessage(context.originalInput())`.
   - **After** the loop completes (success path AND every degraded path), call `memory.append(sessionId, new ConversationTurn(now, "user", originalInput, null))` and `memory.append(sessionId, new ConversationTurn(now, "assistant", finalText, null))`.
   - Cap the prepended history at 6 turns to control context bloat; make it a `@Value("${codemind.memory.conversation.replay-turns:6}") int` field.
4. **Do not** persist memory to disk yet — Prompt 7 handles long-term. This is in-process only.
5. **Compatibility:** existing CLI subcommands (`AskCommand`, `RefactorCommand`, `CommitCommand`, `ChangelogCommand`, `DoCommand`) keep calling the no-`sessionId` overloads — they're one-shot CLI invocations with no follow-up, so they correctly get a fresh session every time. Only the REPL gains continuity.

Tests: update `AgentExecutorTest` (mock `ConversationMemoryService`, assert it's called with the right turns); add a `CliRunnerIntegrationTest` (or unit, depending on existing test shape) that confirms two REPL inputs share a `sessionId`. **100% coverage.**

**Do not:** change `AgentContext` to mutate `sessionId`. It stays final.
**Do not:** prepend memory inside `DirectorAgent.dispatch` — the director is a one-shot classifier; only the specialists' executor loop replays history.

---

## Prompt 7 — `LongTermMemoryService` backed by a separate Chroma collection (Medium risk)

**Goal:** Score-recovery for *Memory Effectiveness (cross-session continuity, personalization)*.

**Files to add:**
- `src/main/java/com/codemind/memory/LongTermMemoryService.java`
- `src/main/java/com/codemind/memory/RememberedFact.java` (record: `id`, `at`, `text`, `tags`)
- `src/main/java/com/codemind/tool/RememberFactTool.java` (new `@Tool`)
- `src/main/java/com/codemind/tool/RecallFactsTool.java` (new `@Tool`)
- `src/main/resources/application.yaml` — add a `codemind.memory.long-term.collection` key (default `codemind_memory`)

**What to change, precisely:**

1. `LongTermMemoryService` uses a **separate** Spring AI `VectorStore` bean than the code-chunk one. Configure a second `ChromaVectorStore` bean in `VectorStoreConfig` whose collection name is `codemind.memory.long-term.collection`. Keep the existing code-chunk store untouched.
2. Public API:
   - `String store(String text, List<String> tags)` — returns the assigned id. **Sanitise** `text` with `PromptGuard.sanitizeRaw(...)` before storage (per CLAUDE.md: sanitise on the way *in*).
   - `List<RememberedFact> recall(String query, int k)` — vector search; returns newest-first when scores tie.
   - `boolean forget(String id)`.
3. `RememberFactTool`: `@Tool(name = "remember")` `@SafeTool(SafetyLevel.CONFIRM_REQUIRED)`. Parameters: `String fact`, `String tagsCsv`. Approval preview shows the fact verbatim.
4. `RecallFactsTool`: `@Tool(name = "recall")` `@SafeTool(SafetyLevel.SAFE)`. Parameters: `String query`, `int topK`.
5. The director / specialists can now organically call these tools; no orchestrator change needed.
6. **Privacy gate:** before storing, reject any fact that matches the `PromptGuard` `[BLOCKED:…]` markers — return `"refused: contains untrusted directives"`.

Tests: positive (store → recall returns the fact), negative (forget on unknown id → false, blocked-content store → refused), and a `@SpringBootTest` + `@Tag("integration")` smoke test against a docker-compose Chroma. **100% coverage** on the non-`@Generated` paths.

**Do not:** share the collection with code chunks. A poisoned remembered-fact could otherwise pollute code search.
**Do not:** auto-remember every user turn — that's `ConversationMemoryService`'s job, and it stays in-process.

---

## Prompt 8 — Replace `ask` mode's RAG with the agentic `QnAAgent` (High risk; user-facing default)

**Goal:** Score-recovery for *Agent Intelligence (tool-using reasoning, follow-up retrieval, accuracy)*. Today `QaService.ask(...)` calls `ReActEngine` (pure RAG, no tools); `QnAAgent` already exists with the full `AgentExecutor` loop but is only reachable via `do qna ...`.

**Files to change:**
- `src/main/java/com/codemind/service/QaService.java`
- `src/main/resources/application.yaml` — add a feature flag

**What to change, precisely:**

1. Add a config flag: `codemind.qa.engine: agent` (values: `rag` | `agent`, default `rag` to preserve current behaviour).
2. Inject `QnAAgent qnaAgent` and the existing `ReActEngine` into `QaService`. In `ask(String)`:
   - If flag is `agent`: call `qnaAgent.ask(question)` and adapt `AgentResult` to the existing `printAnswer(...)` signature (`answer`, `stepCount = result.steps().size()`, `elapsedMs = result.totalDurationMs()`).
   - Else: existing `ReActEngine` path.
3. Update `printAnswer` to show a `(agent | rag)` chip in the footer so the user can see which path produced the answer.
4. Add a `/qa-engine agent|rag` slash command in `CliRunner.handleSlashCommand(...)` for live toggling (writes through a new `QaEngineMode` `@Component` analogous to `SessionMode`).
5. Update `--verbose` rendering in `ResultPrinter` to handle the agent path's ReAct trace (it already does for `do` — reuse).

**Why feature-flagged:** the agent path is slower (5–10x more LLM round trips), can hit `maxSteps`, and depends on a healthy tool surface — flipping the default without an A/B period is exactly how you lose *every* point on Intelligence at demo time. Demo with `codemind.qa.engine=agent`; ship default with `rag` until tools are proven over a week.

Tests: parametrise `QaServiceTest` over both engines. Confirm flag selects the right collaborator. **100% coverage.**

**Do not:** delete `ReActEngine` — it's the safe fallback when the agentic path times out or the LLM endpoint is unreliable.
**Do not:** change `QnAAgent`'s signature — Prompt 6 already added the optional `sessionId` overload.

---

## Prompt 9 — Tighten agentic mode: don't blanket auto-approve destructive tools (High risk; changes contract)

**Goal:** Score-recovery for *Autonomy (safe autonomy, not reckless autonomy)* and *Privacy & Local-First (data security)*. Today `ApprovalService.approved(...)` returns `true` for **everything** in agentic mode — including `write_file` and `git_commit`. An evaluator will mark this down.

**Files to change:**
- `src/main/java/com/codemind/service/ApprovalService.java`
- `src/main/java/com/codemind/cli/ConversationMode.java` (extend the enum if needed)
- `src/main/resources/application.yaml` — add `codemind.agentic.confirm-required-tools: prompt-once-per-tool` (values: `always`, `prompt-once-per-tool`, `never`; default the new safe value)

**What to change, precisely:**

1. In `ApprovalService`, add a `Set<String> autoApprovedTools` (mutable, thread-safe) and a `String policy` from the new config key.
2. Rewrite `approved(ActionRequest req)`:
   - `SAFE` → `true` (unchanged)
   - `preApprovedDepth > 0` → `true` (unchanged)
   - **Ask mode** → always prompt (unchanged)
   - **Agentic mode + `policy == "never"`** → auto-approve (the old, lazy behaviour — kept for users who knowingly opt in)
   - **Agentic mode + `policy == "always"`** → still prompt
   - **Agentic mode + `policy == "prompt-once-per-tool"`** (new default):
     - Extract the tool name from `req.action()` (everything before `:`).
     - If `autoApprovedTools` contains the tool → auto-approve and *log* `"[AUDIT] auto-approved <tool> by session policy"`.
     - Otherwise prompt with 3 options: `y` (this once), `a` (always this session — add to `autoApprovedTools`), `n` (decline).
3. Use `InteractivePrompter.prompt(...)` (same path as today) for the 3-option prompt. Parse case-insensitively; anything not `y`/`a` → declined.
4. `clear` (in `CliRunner.handleClear`) must also clear `autoApprovedTools`.

**Why this is high risk:** the current docs and the JavaDoc on `ApprovalService` literally say "*agentic mode auto-approves and returns true immediately*". You must update:
- The class-level JavaDoc on `ApprovalService`
- Any reference to agentic mode in `CliRunner.printHelp()`
- Any test that asserts agentic mode auto-approves a CONFIRM_REQUIRED tool — those tests need to be updated to set `codemind.agentic.confirm-required-tools=never` or to assert the new prompt-once behaviour.

Tests: all 3 policy values × {SAFE, PREVIEW, CONFIRM_REQUIRED} × {ask, agentic} = the cartesian product, plus the "remember-for-session" path. **100% coverage.**

**Do not:** persist `autoApprovedTools` across runs. Session-scoped only — a new process must re-ask. (Persisting would be an auth-bypass vector.)

---

## Prompt 10 — Offline-chat fallback via Ollama (High risk; second LLM provider)

**Goal:** Score-recovery for *Privacy & Local-First (offline capability)* and *Tool Integration (reliability)*. Today the chat path requires `ai-hub.f1arl.com` reachability; embeddings are already local.

**Files to change:**
- `src/main/java/com/codemind/config/LlmConfig.java` (create if not present, or inside `AgentConfig` — see below)
- `src/main/resources/application.yaml`
- A new `src/main/java/com/codemind/service/ChatRouter.java`
- Every `ChatClient` bean in `AgentConfig` — switch to a `ChatClient.Builder` provided by `ChatRouter` so the routing is one place

**What to change, precisely:**

1. In `application.yaml`, **enable** the Ollama chat side that is currently disabled (`spring.ai.ollama.chat.enabled: true`) behind a feature flag. Add:
   ```yaml
   codemind:
     chat:
       primary: openai          # openai | ollama
       fallback: ollama         # ollama | none
       ollama-model: qwen2.5:7b-instruct
       health-check-timeout-ms: 1500
   ```
2. Build **two** `ChatClient.Builder` candidates at startup — one wired to OpenAI (`spring-ai-starter-model-openai`, the existing config) and one wired to Ollama chat. **Do not** wire the second one to the same builder that the embedding side uses; they're different model types.
3. `ChatRouter` exposes a single method: `ChatClient.Builder builder()`. On each call:
   - Probe the primary endpoint with a `HEAD` (or tiny chat `models` list) with `health-check-timeout-ms` timeout.
   - If healthy → return the primary builder; else if `fallback == "ollama"` → return the ollama builder and `audit.record("chat.fallback", "primary down")`.
   - Memoise the decision for 30 seconds to avoid probing on every prompt.
4. Refactor every `@Bean ChatClient *ChatClient(...)` in `AgentConfig` to take `ChatRouter router` instead of `ChatClient.Builder builder`. Inside each bean: `return router.builder().defaultSystem(prompts.load(...)).build();` — **but** this binds the bean to the *current* primary at startup. To make it dynamic, instead make each `ChatClient` bean a tiny delegating wrapper that calls `router.builder()` on every `prompt()` call — implement as `@Bean(name="qnaChatClient") ChatClient qnaChatClient(ChatRouter router, PromptLoader prompts) { return new RoutingChatClient(router, prompts.load("prompts/qna-system.st")); }`.
5. `RoutingChatClient` is a thin `ChatClient` implementation: every call asks `router.builder()` for the current primary, layers the system prompt on, delegates the call, and returns. It must implement the full `ChatClient` interface — be ready for this to be the largest single class in this prompt.

**Why this is risky:** prompts written for `qwen3.5-35b` (current OpenAI model) may not perform well on `qwen2.5:7b-instruct` (Ollama). Strict-JSON outputs (the director's `IntentClassification`) frequently fail on smaller models. After landing this:
- Re-run the full test suite with `codemind.chat.primary=ollama` to surface prompt regressions on the smaller model.
- Add a system-prompt addendum specifically for the Ollama path (`prompts/_ollama-addendum.st`) loaded only when the router selects ollama — explicit JSON examples, lower verbosity.
- Document the offline-mode quality caveat in the user guide.

Tests: positive (primary healthy → primary used); positive (primary down, fallback healthy → fallback used + audit); negative (both down → clear error message, no crash). Mock health probe via an injected `HttpClient`. Coverage 100%.

**Do not:** silently downgrade. The footer of every answer must show which provider produced it when running in fallback mode.
**Do not:** add a `claude` / `openai-public` / `anthropic` / `google` builder. CLAUDE.md forbids third-party AI APIs — Ollama and the internal `ai-hub.f1arl.com` are the only allowed endpoints.

---

## After all ten land

Re-score against the criteria. Expected delta:

| Criterion | Before | After (target) |
|---|---|---|
| Agent Intelligence | 14 | 21 (tools in `ask`, prompt-guard real, memory replay) |
| Autonomy | 8 | 12 (real rollback, safer agentic, compound chains) |
| Memory | 2 | 9 (conversation + long-term + memory tools) |
| Tool Integration | 10 | 14 (run_tests, find_symbol, remember, recall) |
| System Design | 10 | 13 (audit persisted, rollback real, no placeholders) |
| Privacy & Local-First | 6 | 9 (offline chat, persisted audit, real sanitisation) |
| Innovation | 5 | 7 (prompt-once-per-tool policy, routing chat client) |
| **Total** | **~55** | **~85** |

Run `./mvnw verify` after each prompt. If JaCoCo drops below 100%, the gate fails the build — fix in the same prompt before moving on.
