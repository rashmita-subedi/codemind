# CodeMind: 2-Day Hackathon Plan

## Context

CodeMind is an **organization-internal** CLI tool that indexes codebases and provides AI agents for Q&A, refactoring (including Phase-1 execution), change analysis, and a Director-style orchestrator. Inference is routed through the **F1Soft AI hub** (`ai-hub.f1arl.com`, Ollama-compatible) — no third-party cloud calls. A self-hosted Ollama is supported as a one-line config swap for offline/air-gapped use.

- **Team:** 5 Java/Spring Boot devs (Rashmita, Nischal, Sunaina, Nilima, Shyam), all using Claude Max
- **Stack:** Java 21, **Spring Boot 3.4.x, Spring AI 1.0.x**, Picocli (subcommands), optional JLine REPL, OpenAI-compatible client → AI hub (chat), Ollama in Docker (embeddings), **Chroma in Docker (vector store)**
- **Package:** `com.codemind`
- **Strategy:** Alignment sprint (retarget the existing skeleton to match this plan) → Risk-first vertical slice → Parallel expansion → Polish + demo

> **Architecture decision (post-Day-0 merge):** Vector store is **Chroma** (not PgVector) and embeddings come from **local Ollama** (not the hub — the hub key isn't allowlisted for `nomic-embed-text`). See BRD §1 banner for rationale. Read PgVector/Liquibase mentions below as historical.

> **Important context — read first.** As of the `merged/shyam+alignment` push, the Day-0 alignment is **complete** — package is `com.codemind`, Spring Boot 3.4.1 + Spring AI 1.0, Picocli surface with 7 subcommands, JLine kept as REPL, `ChunkStore` interface with `ChromaChunkStore` (default) + `InMemoryChunkStore` (memory fallback), language-aware classification via `LanguagePatterns`, batch-parallel embedding, `@SafeTool` annotation, privacy banner. **Day-1 starts directly on the real work; the alignment §0 below is historical.**

---

## How 5 Developers Using Claude Max Produce Consistent Code

### The Problem
When 5 developers each use Claude Code, each Claude instance generates different naming conventions, patterns, error handling styles, and import orders. Integration becomes painful.

### The Solution: CLAUDE.md

Claude Code **automatically reads `CLAUDE.md`** from the project root at the start of every session. No one needs to paste prompts or guidelines — it just works.

**When in the workflow:** Dev 1 creates `CLAUDE.md` as part of the skeleton project in Hour 1. Everyone pulls it before starting. Every dev's Claude follows the same rules from that point forward.

**What goes in CLAUDE.md:**
```markdown
# CodeMind

## Project
100% local CLI code intelligence tool. Spring Boot + Spring AI + Picocli + Ollama + PgVector.

## Architecture
- `cli/` — Picocli @Command classes. Inject agent/service, call it, render output. No business logic.
- `agent/core/` — AgentExecutor (ReAct loop), AgentContext, AgentResult, ReActStep.
- `agent/` — Thin agent wrappers (QnAAgent, RefactorAgent, ChangeAnalyzerAgent). Each wraps a ChatClient + AgentExecutor.
- `tool/` — Spring AI @Tool components. Each is a @Component with @Tool methods.
- `indexer/` — Code indexing pipeline (FileWalker, CodeChunker, IndexingService).
- `memory/` — ConversationMemoryService, LongTermMemoryService.
- `config/` — Spring @Configuration classes.

## Conventions
- Java 21. Use records for immutable data. Use sealed interfaces where appropriate.
- All @Tool methods return String. Parameters are String, int, or boolean only.
- All agents return AgentResult from their public methods.
- No Lombok. Use Java records instead.
- No comments unless explaining a non-obvious "why".
- Package: com.codemind

## Tool Pattern
Every tool looks like this:
@Component
public class XxxTool {
    @Tool(description = "One clear sentence")
    public String methodName(@ToolParam(description = "desc") String param) {
        // pure Java implementation
        return "formatted result";
    }
}

## Agent Pattern
Every agent looks like this:
@Component
public class XxxAgent {
    private final ChatClient chatClient;
    private final AgentExecutor executor;
    public AgentResult methodName(String input) {
        return executor.execute(chatClient, new AgentContext(UUID.randomUUID().toString(), "prompt..."));
    }
}

## Command Pattern
Every command looks like this:
@Component
@CommandLine.Command(name = "xxx", description = "desc")
public class XxxCommand implements Runnable {
    @CommandLine.Parameters or @CommandLine.Option
    private String input;
    @Override public void run() { ... }
}

## Do NOT
- Do not add Lombok
- Do not use Spring Shell (we use Picocli)
- Do not add comments explaining what code does
- Do not create interfaces for classes that have only one implementation
- Do not add try-catch unless at system boundaries
```

### Why This Works Better Than Alternatives

| Approach | Problem |
|----------|---------|
| Verbal guidelines | Forgotten, inconsistent, not machine-readable |
| Pasting prompts into each session | Tedious, diverges over time, lost on new sessions |
| Code templates/archetypes | Only helps at creation, not ongoing development |
| **CLAUDE.md (recommended)** | Auto-loaded, version-controlled, shared via git, enforced by every Claude instance |

### Additional Consistency Measure: Shared Skeleton

Before parallel work begins, Dev 1 pushes a skeleton with:
- `pom.xml` (all deps finalized — no one else touches this)
- `application.yml` (all config — no one else touches this)
- `CLAUDE.md` (conventions — everyone reads)
- Empty package directories
- One example tool + one example command (as reference implementations)

Everyone pulls this skeleton. Their Claude reads CLAUDE.md. All generated code follows the same patterns.

---

## Day 0 — Alignment Sprint (2–3 hours, before Day 1 begins)

The existing skeleton was written before the docs were finalized and diverges in concrete ways. This sprint retargets it. **No new features** in this sprint — purely bringing the skeleton into agreement with BRD/PRD/ARCH.

| # | Task | Owner | Effort | Files |
|---|------|-------|--------|-------|
| A1 | Rename package `com.hackathon.codemind` → `com.codemind` (whole tree) | Dev 1 | 20 min | All `.java` under `src/`; update `pom.xml` `groupId` if needed |
| A2 | Downgrade `pom.xml`: Spring Boot `4.0.6` → `3.4.x` (latest patch); Spring AI `2.0.0-M6` → `1.0.x` GA | Dev 1 | 30 min | `pom.xml` only |
| A3 | Verify `application.yaml` still points to F1Soft AI hub (it does — keep `base-url: https://ai-hub.f1arl.com`). Rename env var `OLLAMA_API_KEY` → `CODEMIND_AIHUB_KEY` for honesty (the gateway is OpenAI-compatible, not raw Ollama) | Dev 1 | 10 min | `application.yaml`, `.env` |
| A4 | Add `docker-compose.yml` for `pgvector/pgvector:pg16` and `spring-ai-pgvector-store-spring-boot-starter` to `pom.xml` | Dev 1 | 30 min | new `docker-compose.yml`, `pom.xml` |
| A5 | Delete `LocalEmbeddingService` (the fake hash one). Replace with Spring AI's `OpenAiEmbeddingModel` configured for `nomic-embed-text` against the hub. If the hub doesn't expose embeddings, stand up a local Ollama just for embeddings (Option B in PRD §2.2) | Dev 1 | 1 hr | `LocalEmbeddingService.java` (delete), new `EmbeddingConfig.java` |
| A6 | Add Liquibase + `db.changelog-master.yaml` for `codemind_index_status` and `codemind_long_term_memory` per ARCH §5 | Dev 1 | 30 min | new `src/main/resources/db/changelog/` |
| A7 | Add **Picocli** subcommands (`index`, `ask`, `refactor`, `commit`, `changelog`, `do`, `rollback`) keeping the existing JLine shell as an **optional REPL** mode invoked when no subcommand is given | Dev 5 | 1.5 hr | new `cli/CodemindCli.java`, `cli/IndexCommand.java`, etc.; keep `CliRunner.java` as REPL mode triggered by `--repl` |
| A8 | Extract the existing inline-retrieval logic from `ReActEngine` into a proper `CodeSearchTool` so the executor decomposition (Day 1) has somewhere to land | Dev 1 | 30 min | new `tool/CodeSearchTool.java` |
| A9 | Extend `SafetyLevel` enum from `{SAFE, CONFIRM_REQUIRED}` to `{SAFE, PREVIEW, CONFIRM_REQUIRED}`; add `@SafeTool(SafetyLevel)` annotation | Dev 1 | 15 min | `policy/SafetyLevel.java`, new `policy/SafeTool.java` |

**Exit criteria for Day 0:**
- [ ] `./mvnw clean compile` passes on the renamed/downgraded codebase
- [ ] `docker-compose up -d` starts PgVector; Spring Boot connects on app start
- [ ] App startup prints `Using LLM endpoint: https://ai-hub.f1arl.com`
- [ ] `codemind --help` (Picocli) lists all 7 subcommands; `codemind --repl` enters the existing JLine shell
- [ ] No file under `com.hackathon.codemind` remains

After Day 0, the skeleton matches the docs and Day 1 begins on the assumptions in §1.

---

## Execution Strategy: Risk-First + Vertical Slice

### Why Not Bottom-Up?

Bottom-up (build all components → integrate at the end) risks discovering at Hour 7 that Ollama's tool calling doesn't work with Spring AI. Then you've wasted 6 hours.

### Our Approach

```
Hour 1-2: PROVE IT WORKS (spike)
  → Can qwen3.5 do tool calling through Spring AI? YES/NO
  → Can PgVector store and retrieve embeddings? YES/NO

Hour 3-5: FIRST WORKING DEMO (vertical slice)
  → codemind index . → works
  → codemind ask "question" → answers with citations

Hour 5-8: EXPAND (parallel from working base)
  → Add remaining tools, agents, commands

Day 2: POLISH + DEMO
  → Refactor agent, change analyzer, human-in-the-loop, prompt tuning
```

---

## Day 1: Foundation (Detailed Per-Developer)

---

### Dev 1 — Rashmita (Role: Infrastructure & Integration Owner)

Dev 1 is on the **critical path**. Nothing integrates until her config beans work. **Day 1 starts after the Day-0 alignment sprint is complete** (Dev 1 owns most of A1–A9 above).

| Time | Task | Deliverable | Status |
|------|------|-------------|--------|
| Hour 1 | **SPIKE with Dev 2:** Test native tool calling against the AI hub with a scratch `qwen3.5-35b` ChatClient + a dummy `@Tool` method; verify PgVector store/retrieve round-trip | Decision: native tool calling vs text fallback for AgentExecutor | Shapes Dev 2's work |
| Hour 1-2 | Write `LlmConfig.java` — `OpenAiChatModel` + `OpenAiEmbeddingModel` beans against hub | Hub beans in Spring context | |
| Hour 2 | Write `VectorStoreConfig.java` — `PgVectorStore` bean (768-dim, HNSW, COSINE_DISTANCE) | VectorStore bean working | **UNBLOCKS Dev 3 (tools that need VectorStore) and Dev 4 (IndexingService)** |
| Hour 2-3 | `CodeSearchTool` lives at `tool/CodeSearchTool.java` (extracted in Day 0); ensure it implements semantic search via `PgVectorStore.similaritySearch(...)` and declares `SafetyLevel.SAFE` | Semantic search tool ready | |
| Hour 3 | Push config branch, signal team "config beans ready" | `dev1/config` merged to main | |
| Hour 4-5 | Write `AgentConfig.java` — wire ALL tools + prompts into ChatClient beans; **assert at startup that every `@Tool` method has a `@SafeTool` annotation** | `directorChatClient`, `qnaChatClient`, `refactorChatClient`, `changeAnalyzerChatClient` | **THE integration hub — written AFTER tools exist** |
| Hour 5-6 | Write `QnAAgent.java` (thin wrapper) and `DirectorAgent.java` (intent classifier, single LLM call, strict JSON output schema, low-confidence fallback to CLI menu) | Q&A + Director callable | |
| Hour 7-8 | Integration testing: `codemind index .` and `codemind ask "..."` end-to-end through the real ReAct loop | Day-1 gate hit | **DAY 1 GATE** |

**Dev 1 owns:** pom.xml, application.yaml, docker-compose.yml, CLAUDE.md, Liquibase changelog, CodemindApplication.java, `LlmConfig`, `VectorStoreConfig`, `AgentConfig`, `CodeSearchTool`, `QnAAgent`, **`DirectorAgent`**, **`SafeTool` annotation processor in `AgentConfig`**

---

### Dev 2 — Nischal (Role: Agent Core Engine + Safety Integration)

Dev 2 builds the brain of the system — the ReAct reasoning loop with safety gates and PromptGuard integration.

| Time | Task | Deliverable | Status |
|------|------|-------------|--------|
| Hour 1 | **SPIKE with Dev 1:** Test native tool calling against AI hub | Decision: native vs text-based tool calling | |
| Hour 1 | Write `ReActStep.java` — record(stepNumber, thought, action, actionInput, observation, durationMs) | Data record | No deps |
| Hour 1 | Write `AgentContext.java` — mutable per-invocation state (conversationId, query, steps list, finished flag, maxSteps) | Data class | Depends on ReActStep |
| Hour 1 | Write `AgentResult.java` — record(answer, steps, totalSteps, totalDurationMs, hitMaxSteps) | Data record | Depends on ReActStep |
| Hour 2-4 | Write `AgentExecutor.java` — the ReAct loop with safety + guard integration: | Core engine | Biggest single piece of work |
| | • Build scratchpad from previous steps | | |
| | • Call `ChatClient.prompt()` with query + scratchpad | | |
| | • Parse THOUGHT / FINAL_ANSWER markers | | |
| | • Before tool dispatch: `PromptGuard.sanitize(args)` on every string argument | | |
| | • Read tool's `@SafeTool` level via reflection: `SAFE` → invoke; `PREVIEW` → invoke + render + inject ack into scratchpad; `CONFIRM_REQUIRED` → invoke (tool calls `ApprovalService` internally) | | |
| | • `AuditService.record("tool.invoke", level + tool name)` on every call | | |
| | • Max-step guardrail (10 steps) | | |
| | • Text fallback ACTION/INPUT parser if spike showed native tool calling is unreliable | | |
| Hour 5 | Test AgentExecutor with real hub (after Dev 1 merges config) | Verified ReAct loop | Depends on Dev 1 merge |
| Hour 5-6 | Add self-reflection every 3 steps ("Am I making progress?") | Improved reasoning | |
| Hour 6-7 | Integration: confirm PromptGuard fires on a known injection input and Audit logs it | End-to-end guard verified | |
| Hour 7-8 | Help test `codemind ask` and `codemind do` end-to-end, fix executor issues | Working agent loop | |

**Dev 2 owns:** AgentExecutor, AgentContext, AgentResult, ReActStep, integration of `PromptGuard` + `SafetyLevel` enforcement in the executor

---

### Dev 3 — Sunaina (Role: Tool Developer)

Dev 3 builds all 10 tools. Every tool must declare `@SafeTool(SafetyLevel.X)`. The first 6 are pure Java (no Spring AI deps) — can start immediately.

| Time | Task | Deliverable | Safety | Status |
|------|------|-------------|--------|--------|
| Hour 1 | Pull post-Day-0 main | Local project ready | | |
| Hour 1-2 | `FileReaderTool` — file content with line numbers, optional line range, 500 KB guard | `@Tool` + `@SafeTool(SAFE)` | SAFE | No deps |
| Hour 2 | `FileListTool` — directory tree with depth limit, skips `.git`/`node_modules`/`target`/`build` | `@Tool` + `@SafeTool(SAFE)` | SAFE | No deps |
| Hour 2-3 | `GrepTool` — regex search, capped at 50 matches, no binary | `@Tool` + `@SafeTool(SAFE)` | SAFE | No deps |
| Hour 3 | `GitDiffTool` — modes: unstaged/staged/range via `ProcessBuilder` | `@Tool` + `@SafeTool(PREVIEW)` | **PREVIEW** | No deps |
| Hour 3-4 | `GitLogTool` — commit history, limit + range | `@Tool` + `@SafeTool(SAFE)` | SAFE | No deps |
| Hour 4 | `AstAnalyzerTool` — regex-based Java/Python/JS function+class extraction | `@Tool` + `@SafeTool(SAFE)` | SAFE | No deps |
| Hour 4-5 | **`WriteFileTool`** — atomic write (`.tmp` + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`), `.codemind/backup/<name>.bak`, routes through `ApprovalService` | `@Tool` + `@SafeTool(CONFIRM_REQUIRED)` | **CONFIRM** | No deps (uses ApprovalService) |
| Hour 5 | **`GitCommitTool`** — runs `git commit -m "..."` only after approval; returns the new commit hash | `@Tool` + `@SafeTool(CONFIRM_REQUIRED)` | **CONFIRM** | No deps |
| Hour 5-6 | `MemoryStoreTool` — store/search long-term memory (sanitize content via PromptGuard on store) | `@Tool` + `@SafeTool(SAFE)` | SAFE | Depends on Dev 5's `LongTermMemoryService` |
| Hour 6 | (Optional Day-2) `ExecutePhaseTool` — runs `./mvnw test` via ProcessBuilder after approval; captures stdout/stderr | `@Tool` + `@SafeTool(CONFIRM_REQUIRED)` | **CONFIRM** | Day-2 stretch |
| Hour 7-8 | Test all tools against the sample messy repo; verify safety annotations are picked up by startup scan | All tools verified | | |

**Dev 3 owns:** FileReaderTool, FileListTool, GrepTool, GitDiffTool, GitLogTool, AstAnalyzerTool, **WriteFileTool**, **GitCommitTool**, MemoryStoreTool, (Day-2) ExecutePhaseTool
**Note:** `CodeSearchTool` is Dev 1's (extracted from `ReActEngine` in Day 0).

---

### Dev 4 — Nilima (Role: Indexing Pipeline + Prompts + Day-2 Commands)

Dev 4 builds the indexing pipeline that converts source code into searchable vector embeddings.

| Time | Task | Deliverable | Status |
|------|------|-------------|--------|
| Hour 1 | Pull skeleton from Dev 1 | Local project ready | |
| Hour 1 | Write `ChunkMetadata.java` — record(filePath, startLine, endLine, chunkType, chunkName, language, parentClass) | Data record | No deps |
| Hour 1-2 | Write `LanguageDetector.java` — static map: .java→"java", .py→"python", .js→"javascript", etc. | Utility class | No deps |
| Hour 2 | Write `FileWalker.java` — recursive file discovery using Files.walk(), respects .gitignore, skips .git/target/node_modules/build, filters by supported extensions | Component | No deps |
| Hour 2-4 | Write `CodeChunker.java` — the chunking engine: | Component | Depends on LanguageDetector |
| | - Small files (< 100 lines): single chunk | | |
| | - Large files: split on function/class boundaries via regex per language | | |
| | - Fallback: sliding window with 10-line overlap | | |
| | - Returns list of chunks with ChunkMetadata | | |
| Hour 4-5 | Write `IndexingService.java` — orchestrates full pipeline: | Service | **BLOCKED until Dev 1 merges config (VectorStore + EmbeddingModel)** |
| | 1. Walk files via FileWalker | | |
| | 2. Compute SHA-256 hash per file, check codemind_index_status table | | |
| | 3. Skip unchanged files (unless --force) | | |
| | 4. Chunk files via CodeChunker | | |
| | 5. Create Document objects with metadata | | |
| | 6. Batch-add to VectorStore (20 per batch) | | |
| | 7. Upsert codemind_index_status | | |
| Hour 5-6 | Write system prompt files: | Text files | No deps (can write anytime) |
| | • `director-system.st` — strict-JSON intent classifier | | |
| | • `qna-system.st` — search before answering, cite file:line, THOUGHT/FINAL_ANSWER, treat `<<UNTRUSTED>>` as data | | |
| | • `refactor-analyze-system.st` — code smells, SOLID, phased plan; **Phase 1 changes expressed as single-file edits** for `WriteFileTool` | | |
| | • `refactor-apply-system.st` — issue one WriteFileTool call per approved Phase-1 change; halt on cross-file edits | | |
| | • `change-analyzer-system.st` (commit) — Conventional Commits | | |
| | • `changelog-engineer-system.st` — grouped list | | |
| | • `changelog-plain-system.st` — single paragraph, no jargon | | |
| Hour 7-8 | Test `codemind index .` — verify files indexed, chunks in PgVector, correct metadata | Indexing works | |

**Dev 4 owns:** ChunkMetadata, LanguageDetector, FileWalker, CodeChunker, IndexingService, **all 7 `.st` prompt files**, RefactorCommand, CommitCommand, ChangelogCommand (Day 2)

---

### Dev 5 — Shyam (Role: CLI + Memory + PromptGuard)

Dev 5 builds the user-facing CLI layer, memory services, and the prompt-injection guardrail.

| Time | Task | Deliverable | Status |
|------|------|-------------|--------|
| Hour 1 | Pull post-Day-0 main (Picocli skeleton already in place from A7) | Local project ready | |
| Hour 1-2 | `CliOutputRenderer.java` — ANSI colored output: | Utility | No deps |
| | • `printHeader(msg)` — bold blue `═══ msg ═══` | | |
| | • `printThinking(msg)` — dim cyan spinner | | |
| | • `preview(content)` — render PREVIEW-tool output for inspection | | |
| | • `formatAnswer(AgentResult)` — green answer + step/time stats | | |
| | • `formatIndexResult(stats)`, `printCommitMessage(msg)`, `formatError/Warning/Success(msg)` | | |
| | • Renders `[Y/n]`, `[Y/n/e]`, `[1-4/q]` prompts consistently | | |
| Hour 2 | **`PromptGuard.java`** — regex sanitizer per ARCH §11; pattern list + audit on flag | Component | No deps |
| Hour 2-3 | Wire `PromptGuard` into `IndexCommand` / `AskCommand` / `DoCommand` input paths | Sanitization at CLI boundary | |
| Hour 3 | `IndexCommand.java` — `codemind index <path> [--force]` | CLI command | Depends on IndexingService (Dev 4) — placeholder fine |
| Hour 3-4 | `AskCommand.java` — `codemind ask "<question>" [--verbose]` | CLI command | Depends on QnAAgent (Dev 1) — placeholder fine |
| Hour 4 | **`DoCommand.java`** — `codemind do "<input>"`; dispatches to `DirectorAgent` | CLI command | Depends on DirectorAgent (Dev 1) |
| Hour 4 | **`RollbackCommand.java`** — `codemind rollback`; restores from `.codemind/backup/` | CLI command | No deps (filesystem only) |
| Hour 4-5 | `ConversationMemoryService.java` — Spring AI's `MessageWindowChatMemory` + `InMemoryChatMemoryRepository`, maxMessages=20 | Service | Depends on Spring AI (Dev 1 config) |
| Hour 5-6 | `LongTermMemoryService.java` — PgVector-backed: `store(content, category)` (sanitize via PromptGuard first), `search(query)` | Service | Depends on VectorStore (Dev 1 config) |
| Hour 7-8 | Wire commands to real agents (after Dev 1 + Dev 2 merge), test CLI flow incl. injection-attempt manual test | Working CLI | |

**Dev 5 owns:** CliOutputRenderer, CodemindCli (Picocli main), IndexCommand, AskCommand, **DoCommand**, **RollbackCommand**, ConversationMemoryService, LongTermMemoryService, **PromptGuard**

---

### Day 1 Sync Points

| When | What | Who |
|------|------|-----|
| Start of Day 1 | **Day-0 alignment sprint complete**; main is on SB 3.4 / Spring AI 1.0 / `com.codemind` | Dev 1 → All |
| End of Hour 1 | Spike result announced: native tool calling YES/NO | Dev 1 + Dev 2 → All |
| End of Hour 3 | Config beans ready, pushed to main; Picocli subcommands available as placeholders | Dev 1 → Dev 3, Dev 4, Dev 5 |
| End of Hour 4 | `PromptGuard` merged; available for executor and CLI to wire in | Dev 5 → Dev 2, all CLI commands |
| Hour 5-6 | **MERGE CEREMONY** — merge order: Dev 2 (executor) → Dev 3 (tools) → Dev 4 (indexer + prompts) → Dev 5 (CLI/memory/guard) → Dev 1 (agents/wiring last) | All |
| End of Hour 8 | **DAY 1 GATE:** `codemind index .` + `codemind ask "question"` work end-to-end with citations | All |

---
---

## Day 2: Agents, Polish, Demo (Detailed Per-Developer)

---

### Dev 1 — Rashmita (Role: Remaining Agents + Integration)

| Time | Task | Deliverable | Blocked By |
|------|------|-------------|------------|
| Hour 1 | `RefactorAgent.java` — two methods: `analyze(path) → AgentResult`, `applyPhaseOne(plan) → AgentResult`; the latter loads `refactor-apply-system.st` and lets the executor dispatch `WriteFileTool` calls under the safety gate | Agent | Nothing |
| Hour 1-2 | `ChangeAnalyzerAgent.java` — three methods: `generateCommitMessage()`, `generateChangelog(range, plain=false)`, `generateChangelog(range, plain=true)` | Agent | Nothing |
| Hour 2 | Integration-test `DirectorAgent` chain (`COMPOUND` intent) end-to-end: refactor → confirm gate → commit | Director chain works | |
| Hour 3-4 | Cross-agent integration testing + bug-fix sweep | Stable agents | Dev 4 must have commands wired |
| Hour 5-6 | System-prompt tuning with Dev 4 (pair) — especially: refactor severity tags, Phase-1 edit format, plain-changelog tone | Tuned prompts | |
| Hour 7-8 | Demo prep: build JAR, run the full proposal-demo script start-to-finish | Demo-ready build | |

---

### Dev 2 — Nischal (Role: AgentExecutor Polish)

| Time | Task | Deliverable | Blocked By |
|------|------|-------------|------------|
| Hour 1-2 | Add human-in-the-loop to AgentExecutor — for write_file and execute_phase tools, pause and ask user confirmation via CLI prompt | HitL flow | Nothing |
| Hour 2-3 | If text-based fallback was needed: polish the ACTION/INPUT parser, handle edge cases (malformed JSON, missing fields) | Robust fallback | Nothing |
| Hour 3-4 | Improve self-reflection prompt — test with real queries, ensure the agent doesn't loop uselessly | Better reasoning | Nothing |
| Hour 5-6 | Test ReAct loop with each of the 3 agents, fix any agent-specific issues | Verified agents | |
| Hour 7-8 | Demo rehearsal, help troubleshoot | Demo-ready | |

---

### Dev 3 — Sunaina (Role: Tool Testing + Fixes)

| Time | Task | Deliverable | Blocked By |
|------|------|-------------|------------|
| Hour 1-2 | Test ALL 8 tools against a real repository (not just sample) | Bug list | Nothing |
| Hour 2-3 | Fix edge cases: large files in FileReaderTool (>500KB guard), binary files, empty repos | Robust tools | Nothing |
| Hour 3-4 | Test CodeSearchTool relevance — are the right chunks being returned? Adjust similarity threshold if needed | Tuned search | Nothing |
| Hour 5-6 | Help Dev 4 test refactor + commit + changelog commands | Command testing | |
| Hour 7-8 | Demo rehearsal, prepare demo repository with good test scenarios | Demo repo | |

---

### Dev 4 — Nilima (Role: Remaining Commands + Prompts)

| Time | Task | Deliverable | Blocked By |
|------|------|-------------|------------|
| Hour 1 | `RefactorCommand.java` — `codemind refactor <file> [--verbose] [--apply]`; after analysis, prompts `Apply Phase 1 (N safe extractions)? [Y/n]`; on Y, calls `RefactorAgent.applyPhaseOne`; `--apply` short-circuits the plan-level prompt (per-file approval still happens in manual mode) | CLI command | **Dev 1 must finish RefactorAgent.analyze + applyPhaseOne** |
| Hour 2 | `CommitCommand.java` — `codemind commit [--auto]`; reads `git diff --staged` via `GitDiffTool` (PREVIEW), generates message via `ChangeAnalyzerAgent.generateCommitMessage()`, prompts `[Y/n/e]`; `Y` calls `GitCommitTool` (CONFIRM → approved via the same mode flag) | CLI command | **Dev 1 must finish ChangeAnalyzerAgent** |
| Hour 2-3 | `ChangelogCommand.java` — `codemind changelog <range> [--plain]`; passes the `plain` flag through to `ChangeAnalyzerAgent.generateChangelog(range, plain)` | CLI command | **Dev 1 must finish ChangeAnalyzerAgent** |
| Hour 3-4 | Test all 3 commands against the demo repo: refactor with `--apply`, commit Y/n/e flow, changelog default + `--plain` | Verified commands | |
| Hour 5-6 | System-prompt tuning with Dev 1 (pair) — highest-leverage activity: | Tuned prompts | |
| | • "where is rate limiting?" → cited file:line answer | | |
| | • Refactor `MessyService.java` → severity-tagged findings AND a Phase-1 plan that the executor can dispatch as `WriteFileTool` calls | | |
| | • Commit on a known staged change → Conventional Commits format | | |
| | • Changelog `--plain` on a 5-commit range → readable paragraph | | |
| Hour 7-8 | Demo rehearsal, write/finalize demo script with exact commands | Demo script | |

---

### Dev 5 — Shyam (Role: CLI Polish + UX)

| Time | Task | Deliverable | Blocked By |
|------|------|-------------|------------|
| Hour 1-2 | Add progress indicators for slow operations (indexing progress bar, "thinking..." spinner) | Better UX | Nothing |
| Hour 2-3 | CommitCommand confirmation flow: show diff summary → proposed commit message → [Y]es / [n]o / [e]dit prompt → execute or abort | HitL UX | **Dev 4 must have CommitCommand shell** |
| Hour 3-4 | Polish ALL command outputs — consistent colors, aligned formatting, timing info | Polished CLI | |
| Hour 4 | Add `--verbose` flag to all commands — shows ReAct step trace when enabled | Debug mode | |
| Hour 5-6 | Test full CLI flow as a user would — catch UX issues, confusing output, missing error messages | UX verified | |
| Hour 7-8 | Record backup demo video, final testing | Backup video | |

---

### Day 2 Sync Points

| When | What | Who |
|------|------|-----|
| End of Hour 2 | Dev 1 finishes agents → unblocks Dev 4 for commands | Dev 1 → Dev 4 |
| End of Hour 3 | Dev 4 finishes command shells → unblocks Dev 5 for UX | Dev 4 → Dev 5 |
| Hour 4-5 | **MERGE CEREMONY** — same order: Dev 2 → Dev 3 → Dev 4 → Dev 5 → Dev 1 | All |
| Hour 5-6 | All 5 commands tested end-to-end | All |
| Hour 7-8 | **FINAL GATE:** Demo rehearsal passes, backup video recorded | All |

---

### Day 2 Dependency Chain (Visual)

```
Dev 1: [RefactorAgent + ChangeAnalyzerAgent]──→ Dev 4 unblocked
                                                    │
Dev 4: ────────── WAITING ──────────[Commands]──→ Dev 5 unblocked
                                                    │
Dev 5: ──[Progress/Polish]── WAITING ──[CommitCommand UX + Polish]

Dev 2: [HitL + Fallback + Reflection] ──── independent ────
Dev 3: [Tool testing + fixes] ──── independent ────
```

---

## File Ownership (Who Touches What)

Single-owner files prevent merge conflicts:

| Owner | Files (ONLY this person edits these) |
|-------|--------------------------------------|
| Dev 1 | pom.xml, application.yaml, docker-compose.yml, CLAUDE.md, `AgentConfig`, `LlmConfig`, `VectorStoreConfig`, CodemindApplication, Liquibase changelog, `QnAAgent`, `RefactorAgent`, `ChangeAnalyzerAgent`, **`DirectorAgent`**, `CodeSearchTool`, **`SafetyLevel` enum + `@SafeTool` annotation** |
| Dev 2 | `AgentExecutor`, `AgentContext`, `AgentResult`, `ReActStep` |
| Dev 3 | `FileReaderTool`, `FileListTool`, `GrepTool`, `GitDiffTool`, `GitLogTool`, `AstAnalyzerTool`, **`WriteFileTool`**, **`GitCommitTool`**, `MemoryStoreTool`, (Day-2) `ExecutePhaseTool` |
| Dev 4 | `LanguageDetector`, `ChunkMetadata`, `FileWalker`, `CodeChunker`, `IndexingService`, all 7 `.st` prompt files, `RefactorCommand`, `CommitCommand`, `ChangelogCommand` |
| Dev 5 | `CliOutputRenderer`, `IndexCommand`, `AskCommand`, **`DoCommand`**, **`RollbackCommand`**, Picocli main class, `ConversationMemoryService`, `LongTermMemoryService`, **`PromptGuard`** |

Shared (no single owner — touch only what your story requires):

| Files | Why |
|-------|-----|
| `policy/SafetyLevel.java`, `policy/SafeTool.java`, `policy/ActionRequest.java`, `policy/ExecutionMode.java` | Cross-cutting safety types. Dev 1 is the **default approver** of changes here. |
| `service/ApprovalService.java`, `service/AuditService.java` | Cross-cutting. Default approver: Dev 1. |

**Rule: If you need a change in someone else's file, ask them — don't edit it yourself.**

---

## Key Config Files

### pom.xml key dependencies (as merged)
```
spring-boot-starter, spring-boot-starter-web            (Spring Boot 3.4.1)
spring-ai-starter-model-openai                          (Spring AI 1.0.0 — chat → F1Soft AI hub)
spring-ai-starter-model-ollama                          (Spring AI 1.0.0 — embeddings → local Ollama in Docker)
spring-ai-starter-vector-store-chroma                   (Spring AI 1.0.0 — vector store → local Chroma in Docker)
info.picocli:picocli + picocli-spring-boot-starter      (subcommand surface)
org.jline:jline                                          (optional REPL mode)
spring-boot-starter-test (test scope)
```

### application.yaml (the actual config on `merged/shyam+alignment`)
```yaml
spring:
  application:
    name: codemind
  main:
    banner-mode: off
    web-application-type: none
    lazy-initialization: true
  ai:
    model:
      chat: openai
      embedding: ollama
    openai:                                  # chat → F1Soft hub
      api-key: ${CODEMIND_AIHUB_KEY:}
      base-url: https://ai-hub.f1arl.com
      chat:
        options:
          model: qwen3.5-35b
          max-tokens: 4096
      embedding:
        enabled: false                       # chat-only on the hub
    ollama:                                  # embeddings → local Ollama
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      chat:
        enabled: false                       # embeddings-only locally
      embedding:
        options:
          model: ${OLLAMA_EMBED_MODEL:all-minilm}   # opt into nomic-embed-text for higher recall
      init:
        pull-model-strategy: when_missing
        timeout: PT10M
        embedding:
          include: true
    vectorstore:
      chroma:
        client:
          host: ${CHROMA_HOST:http://localhost}
          port: ${CHROMA_PORT:8000}
        collection-name: ${CHROMA_COLLECTION:codemind}
        tenant-name: ${CHROMA_TENANT:default_tenant}
        database-name: ${CHROMA_DATABASE:default_database}
        initialize-schema: true
codemind:
  execution:
    mode: manual                             # manual | auto
  vector-store: ${CODEMIND_VECTOR_STORE:chroma}   # `memory` for the no-Docker fallback
  shell:
    interactive: true                        # JLine REPL when no subcommand is given
  promptguard:
    enabled: true
```

> **No-Docker fallback (early dev only):** `CODEMIND_VECTOR_STORE=memory java -jar codemind.jar` swaps to `InMemoryChunkStore` with the hash-based `LocalEmbeddingService`. Useful for testing the Picocli surface without standing up Docker; semantic-search quality drops to keyword-match because the embeddings are fake.

### docker-compose.yml (as merged)
```yaml
services:
  chroma:
    image: chromadb/chroma:0.6.3
    container_name: codemind-chroma
    restart: unless-stopped
    ports: ["8000:8000"]
    environment:
      IS_PERSISTENT: "TRUE"
      ANONYMIZED_TELEMETRY: "FALSE"
    volumes: [chroma-data:/chroma/chroma]
  ollama:
    image: ollama/ollama:latest
    container_name: codemind-ollama
    restart: unless-stopped
    ports: ["11434:11434"]
    environment:
      OLLAMA_NUM_PARALLEL: "4"
      OLLAMA_KEEP_ALIVE: "30m"
      OLLAMA_MAX_LOADED_MODELS: "2"
    volumes: [ollama-data:/root/.ollama]
  ollama-pull:
    image: ollama/ollama:latest
    container_name: codemind-ollama-pull
    depends_on: [ollama]
    entrypoint: ["/bin/sh", "-c"]
    command:
      - |
        set -e
        until ollama list >/dev/null 2>&1; do
          echo "waiting for ollama..."
          sleep 2
        done
        ollama pull all-minilm
        ollama pull nomic-embed-text
    environment:
      OLLAMA_HOST: http://ollama:11434
    restart: "no"
volumes:
  chroma-data:
  ollama-data:
```

---

## Testing Strategy

### Core Rule: "Write It → Test It → Push It"

Every developer asks Claude Code to generate JUnit tests alongside every feature and class. **No component moves to "done" without passing tests at 100% coverage, including both positive and negative cases.** This costs ~2 minutes per class (Claude generates the test) and catches bugs hours earlier than end-of-day testing.

### Non-Negotiables

- **JUnit after every feature/class** — testing happens immediately, not batched for later.
- **100% coverage** — every line and branch is exercised. `./mvnw verify` runs the JaCoCo gate and fails the build below 100% line + branch coverage.
- **Positive AND negative cases for every method** — at least one valid-input/correct-result test and at least one bad/edge-input/correct-rejection test. Branch-heavy methods get one test per branch.

### What Claude Code Does For You

When a developer writes a class, they tell Claude: **"Write JUnit tests for this class with 100% coverage — both positive and negative cases"**. Claude generates:
- A test class in `src/test/java/com/codemind/` mirroring the source package
- `@TempDir` based filesystem tests for file-handling tools
- Mocked `ChatClient` tests for agent logic
- Mocked `ApprovalService` (both `true`/`false`) tests for CONFIRM_REQUIRED tools so both branches are covered
- **Positive** assertions covering the happy path **and** **negative** assertions covering edge cases and error conditions

**This is not extra work — it's work that would happen at Hour 7-8 anyway, but moved earlier when fixes are cheap.**

### Test Dependencies (What's in pom.xml)

```xml
<!-- Dev 1 includes these in the skeleton pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<!-- Provides: JUnit 5, Mockito, AssertJ, SpringBootTest, @TempDir -->
```

**JaCoCo enforces the 100% coverage gate** (Dev 1 adds this plugin to the skeleton `pom.xml`):

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution><goals><goal>prepare-agent</goal></goals></execution>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>report</goal><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>1.00</minimum></limit>
                            <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>1.00</minimum></limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

`./mvnw verify` generates `target/site/jacoco/index.html` and fails the build if coverage drops below 100% (line + branch). Integration-only beans that need live Ollama/Chroma may be excluded from the bundle so the unit gate stays runnable without infrastructure.

---

### Unit Tests Per Developer (No External Deps — Run Instantly)

These use **no Ollama, no Postgres, no Docker** — pure Java logic tests.

#### Dev 2 — Nischal (Agent Core)

| Class | What to Test | When |
|-------|-------------|------|
| `ReActStep` | Record construction, field accessors, equals/hashCode | After writing it (Hour 2) |
| `AgentContext` | Initial state, addStep(), isFinished transitions, step count | After writing it (Hour 2) |
| `AgentResult` | Construction from steps list, totalDuration calculation, hitMaxSteps flag | After writing it (Hour 2) |
| `AgentExecutor` (parser only) | Parse THOUGHT: / FINAL_ANSWER: from sample strings, multiline THOUGHT, missing markers, malformed input, max-step guardrail triggers | After writing parser logic (Hour 3-4) |

**How to test AgentExecutor without Ollama:** Mock the `ChatClient` to return canned response strings. This tests your parsing and loop logic — the hardest part — without waiting for Dev 1's config.

```java
// Example: Dev 2 asks Claude to generate this
@ExtendWith(MockitoExtension.class)
class AgentExecutorTest {
    @Mock ChatClient chatClient;
    @Mock ChatClient.CallResponseSpec callResponse;

    @Test
    void parsesThoughtAndFinalAnswer() { ... }

    @Test
    void stopsAtMaxSteps() { ... }

    @Test
    void handlesMalformedResponse() { ... }
}
```

#### Dev 3 — Sunaina (Tools)

| Class | What to Test | When |
|-------|-------------|------|
| `FileReaderTool` | Read existing file, line range, file not found, large file guard (>500KB), binary file detection | Immediately after writing (Hour 1-2) |
| `FileListTool` | Tree listing, depth limit, skips .git/node_modules/target, empty dir | Immediately after writing (Hour 2) |
| `GrepTool` | Regex match, no matches, max 50 results cap, invalid regex handling | Immediately after writing (Hour 2-3) |
| `GitDiffTool` | Diff output format (use a temp git repo initialized in @TempDir) | Immediately after writing (Hour 3) |
| `GitLogTool` | Log format, empty repo, limit parameter | Immediately after writing (Hour 3-4) |
| `AstAnalyzerTool` | Java class extraction, Python function extraction, JS function extraction, unsupported language | Immediately after writing (Hour 4) |

**All tools use `@TempDir` — create temp files/directories, run the tool, verify output.** No mocking needed since these are pure I/O.

```java
// Example: Dev 3 asks Claude to generate this
class FileReaderToolTest {
    @TempDir Path tempDir;

    @Test
    void readsFileWithLineNumbers() {
        Files.writeString(tempDir.resolve("Hello.java"), "public class Hello {}");
        var result = new FileReaderTool().readFile(tempDir.resolve("Hello.java").toString(), 0, 0);
        assertThat(result).contains("public class Hello");
    }

    @Test
    void returnsErrorForMissingFile() { ... }
    @Test
    void guardsAgainstLargeFiles() { ... }
}
```

#### Dev 4 — Nilima (Indexing)

| Class | What to Test | When |
|-------|-------------|------|
| `ChunkMetadata` | Record construction, all fields populated | After writing (Hour 1) |
| `LanguageDetector` | .java→"java", .py→"python", .js→"javascript", unknown extension → null/unknown | After writing (Hour 1-2) |
| `FileWalker` | Finds .java files, skips .git/target/node_modules, respects depth, empty directory | After writing (Hour 2) |
| `CodeChunker` | Small file → single chunk, large Java file → splits on class/method boundaries, fallback sliding window, chunk metadata correctness | After writing (Hour 2-4) |

**CodeChunker is the most logic-heavy — test it thoroughly.** Create sample Java/Python/JS files in `src/test/resources/samples/` and verify chunking output.

```java
// Example: Dev 4 asks Claude to generate this
class CodeChunkerTest {
    @Test
    void smallFileIsSingleChunk() {
        String code = "public class Small {\n    void hello() {}\n}";
        var chunks = new CodeChunker(new LanguageDetector()).chunk("Small.java", code);
        assertThat(chunks).hasSize(1);
    }

    @Test
    void largeJavaFileSplitsOnMethodBoundaries() { ... }
    @Test
    void fallsBackToSlidingWindowForUnknownLanguage() { ... }
}
```

#### Dev 5 — Shyam (CLI + Memory)

| Class | What to Test | When |
|-------|-------------|------|
| `CliOutputRenderer` | Header format, error format, success format (test the string output, not ANSI rendering) | After writing (Hour 1-2) |

---

### Integration Tests Per Developer (Need Docker/Ollama — Run After Hour 4)

These require running infrastructure. Written after merge ceremony.

| Test | What It Proves | Who | When |
|------|---------------|-----|------|
| `OllamaHealthTest` | Ollama is reachable, model responds | Dev 1 | Hour 2-3 |
| `VectorStoreIntegrationTest` | Embed a string → store → search → get result back | Dev 1 | Hour 3 |
| `IndexingServiceIntegrationTest` | Walk a 5-file sample dir → chunk → embed → stored in PgVector → query returns results | Dev 4 | Hour 5 (after config merge) |
| `AgentExecutorIntegrationTest` | Send real query through ReAct loop → Ollama responds → tools called → answer returned | Dev 2 | Hour 5-6 (after config merge) |
| `EndToEndTest` | `index` → `ask` pipeline works: index sample repo, ask question, get cited answer | Dev 1 | Hour 7-8 |

**Integration tests use `@SpringBootTest` with real beans — no mocking.**

```java
@SpringBootTest
@ActiveProfile("test") // optional: lighter config for faster startup
class IndexingServiceIntegrationTest {
    @Autowired IndexingService indexingService;

    @Test
    void indexesSampleRepoAndStoresChunks() {
        var stats = indexingService.index(Path.of("src/test/resources/sample-repo"), false);
        assertThat(stats.filesIndexed()).isGreaterThan(0);
        assertThat(stats.chunksStored()).isGreaterThan(0);
    }
}
```

---

### Manual Test Checkpoints

Run these at each sync point. **No checkpoint passes until all commands in it succeed.**

#### Checkpoint 1: After Hour 4 Merge (Config Ready)

```bash
# Verify infrastructure
docker ps                                    # postgres running
curl -s http://localhost:11434/api/tags      # ollama has qwen3.5 + nomic-embed-text

# Verify Spring context loads
./mvnw spring-boot:run                       # app starts without bean errors (Ctrl+C after)

# Verify unit tests pass
./mvnw test                                  # ALL unit tests green
```

#### Checkpoint 2: After Hour 5-6 Merge Ceremony

```bash
# All unit tests still pass after merge
./mvnw test

# Test indexing manually
./mvnw spring-boot:run
# In another terminal:
java -jar target/codemind.jar index src/test/resources/sample-repo
# Expected: "Indexed X files, Y chunks in Z seconds"

# Verify chunks in database
docker exec -it <postgres-container> psql -U codemind -c "SELECT count(*) FROM vector_store;"
# Expected: > 0
```

#### Checkpoint 3: Day 1 Gate (Hour 8)

```bash
# Full vertical slice
java -jar target/codemind.jar index .
java -jar target/codemind.jar ask "What does AgentExecutor do?"
# Expected: Answer with file:line citations, completes in < 60 seconds

# Verbose mode shows reasoning
java -jar target/codemind.jar ask "What does AgentExecutor do?" --verbose
# Expected: Shows THOUGHT steps + tool calls
```

#### Checkpoint 4: Day 2 Gate (Hour 8)

```bash
# All 5 commands work
java -jar target/codemind.jar index .
java -jar target/codemind.jar ask "Where is error handling?"
java -jar target/codemind.jar refactor src/main/java/com/codemind/tool/GrepTool.java
java -jar target/codemind.jar commit
java -jar target/codemind.jar changelog HEAD~3..HEAD

# Human-in-the-loop works
java -jar target/codemind.jar commit
# Expected: Shows proposed message → prompts Y/n/e → user confirms → committed
```

---

### Test File Ownership

Mirrors the source ownership — each developer owns tests for their own classes:

| Owner | Test Files |
|-------|-----------|
| Dev 1 | OllamaHealthTest, VectorStoreIntegrationTest, EndToEndTest |
| Dev 2 | ReActStepTest, AgentContextTest, AgentResultTest, AgentExecutorTest, AgentExecutorIntegrationTest |
| Dev 3 | FileReaderToolTest, FileListToolTest, GrepToolTest, GitDiffToolTest, GitLogToolTest, AstAnalyzerToolTest, MemoryStoreToolTest |
| Dev 4 | ChunkMetadataTest, LanguageDetectorTest, FileWalkerTest, CodeChunkerTest, IndexingServiceIntegrationTest |
| Dev 5 | CliOutputRendererTest |

---

### Sample Test Resources

Dev 1 creates `src/test/resources/sample-repo/` in the skeleton with:
```
sample-repo/
├── Calculator.java      (simple class with 2 methods — ~30 lines)
├── utils/
│   └── StringHelper.java (utility class — ~20 lines)
├── app.py               (Python file for language detection testing)
└── index.js             (JS file for multi-language testing)
```

This gives every developer a consistent test target.

---

### Updated Developer Schedules (Test-Integrated)

The per-developer tables above (Day 1 sections) should be read with this rule:

> **After writing each class, immediately ask Claude Code: "Write JUnit tests for [ClassName] with 100% coverage — both positive and negative cases". Run `./mvnw verify` (which enforces the 100% coverage gate) before moving to the next class. This adds ~2-3 minutes per class.**

The time allocations in the original tables already have enough slack for this — writing + testing a pure Java tool takes roughly the same time as writing it without tests, because Claude generates the test code.

---

## Risks

| Risk | Mitigation |
|------|-----------|
| Tool calling fails with qwen3.5 | Discovered in Hour 1 spike, not Hour 7. Fallback ready. |
| CPU inference slow | Pre-warm model, max 10 steps, small demo repo |
| Integration conflicts | File ownership table + fixed merge order + CLAUDE.md conventions |
| Inconsistent Claude output across devs | CLAUDE.md auto-loaded by every Claude Code session |
| AgentConfig wiring breaks | Dev 1 sole owner, writes AFTER all components exist |

---

## Immediate Next Steps

1. Create BRD, PRD, Architecture docs (with Mermaid diagrams)
2. Then begin Day 1 Hour 1: Skeleton + CLAUDE.md + Spike
