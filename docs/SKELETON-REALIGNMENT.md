# Skeleton Realignment — Per-Dev Handoff

> ### ⚠️ STATUS: Day-0 alignment is COMPLETE (merged on branch `merged/shyam+alignment`).
>
> What's in main now:
> - Package `com.codemind` ✅
> - Spring Boot 3.4.1 + Spring AI 1.0.0 ✅
> - Picocli surface (7 subcommands) + JLine REPL fallback ✅
> - **Chroma + Ollama in docker-compose** (vector store + embeddings) ✅ — *not* PgVector
> - `ChunkStore` abstraction with `ChromaChunkStore` (default) + `InMemoryChunkStore` (memory fallback) ✅
> - Language-aware classification via `LanguagePatterns` ✅
> - Privacy banner ✅
> - `policy/SafetyLevel` (3 values) + `@SafeTool` annotation + `CodeSearchTool` ✅
> - All docs in `docs/`, `CLAUDE.md` at root ✅
>
> The Day-0 sprint (§2 below) is now **historical reference**. Devs pull `merged/shyam+alignment` and skip to Day-1 work. See PLAN.md §1 for the per-dev Day-1 schedule.

**Audience:** the 5-person team (Rashmita, Nischal, Sunaina, Nilima, Shyam) starting work on `codeMind/`.
**Purpose:** historical record of what changed between the bootstrap skeleton and the current main branch.

---

## 1. State of the World (read this first)

### What the skeleton has today (good, keep)
- Spring Boot project skeleton with Maven wrapper, Dockerfile, basic packaging.
- `policy/` package with `SafetyLevel`, `ExecutionMode`, `ActionRequest`.
- `service/ApprovalService` (with `Scanner`-based prompt and mode flag) and `service/AuditService` (println-based; OK for hackathon).
- `cli/CliRunner` (JLine-based interactive shell) — **keep as the REPL mode** (`codemind --repl`).
- `cli/CommandParser` and `cli/ConversationMode` — reusable inside the REPL.
- `model/CodeChunk` record.
- `retrieval/InMemoryChunkStore` + `IndexedCodeChunk` — keep as a reference for what the new PgVector-backed version replaces; the chunking logic itself is fine.
- `service/IndexService` — file walking and chunking logic is salvageable.

### What must change (the seven hard misalignments)
| # | Skeleton today | Target (per docs) |
|---|---|---|
| 1 | Package `com.hackathon.codemind` | `com.codemind` |
| 2 | Spring Boot **4.0.6** + Spring AI **2.0.0-M6** | Spring Boot **3.4.x** + Spring AI **1.0.x** |
| 3 | `application.yaml` uses `spring-ai-starter-model-openai` against `https://ai-hub.f1arl.com` with `OLLAMA_API_KEY` env var | Same starter & URL (correct), but rename env to `CODEMIND_AIHUB_KEY` for honesty |
| 4 | `LocalEmbeddingService` (fake hash → 384-dim) | Real `OpenAiEmbeddingModel` against `nomic-embed-text` via the hub, 768-dim. PgVector storage. |
| 5 | `InMemoryChunkStore` with Java-serialized `.codemind/index.bin` | `PgVectorStore` (Spring AI starter) on PostgreSQL via docker-compose |
| 6 | Single `ReActEngine.answer()` — one LLM call with decorative steps; no tools, no loop, no reflection | Real `AgentExecutor` with multi-step ReAct, tool calling, PromptGuard sanitization, SafetyLevel gating, self-reflection every 3 steps, max-step guardrail. Three specialist agents (`QnA`, `Refactor`, `ChangeAnalyzer`) + `DirectorAgent`. |
| 7 | `RefactorService.planAndExecute` prints 3 hard-coded labels; `ChangeService.commit/changelog` print 1 line each | Real implementations: RefactorAgent (analyze + applyPhaseOne via WriteFileTool); ChangeAnalyzerAgent (commit + changelog default/`--plain`). Tools: GitDiffTool, GitLogTool, WriteFileTool, GitCommitTool. |

### What's missing entirely (the new components)
- **`DirectorAgent`** + `director-system.st` (Epic 8)
- **`PromptGuard`** + `<<UNTRUSTED>>` convention in every `.st` (Epic 9)
- **`@SafeTool` annotation** + startup scan (Epic 10)
- **`WriteFileTool`**, **`GitCommitTool`**, optional `ExecutePhaseTool` (Story 4.4, Story 5.1)
- **`RollbackCommand`** + `.codemind/backup/` lifecycle (Story 4.5)
- **`ConversationMemoryService`** + **`LongTermMemoryService`** (Epic 3)
- **`FileReaderTool`, `FileListTool`, `GrepTool`, `GitDiffTool`, `GitLogTool`, `AstAnalyzerTool`, `MemoryStoreTool`, `CodeSearchTool`** (Epic 7)
- **`DoCommand`** + Picocli subcommands for `index`, `ask`, `refactor`, `commit`, `changelog`, `rollback` (Epic 6)
- **`changelog-plain-system.st`** alongside the engineer-friendly one (Story 5.2)

---

## 2. Day 0 — Alignment Sprint (2–3 hours, before Day 1 starts)

This sprint is **structural only — no new features**. Goal: the skeleton compiles, runs, points at the right infrastructure, and uses the right namespaces. Nothing more.

### Step A1 — Package rename `com.hackathon.codemind` → `com.codemind`
**Owner:** Dev 1 · **Effort:** 20 min · **Files:** all `.java` under `src/`, `pom.xml` `groupId` (optional — can stay `com.hackathon`)

```bash
# Inside D:\hackathon\codeMind
# Easiest: use your IDE's "Refactor → Rename package" — that updates imports and directory structure together.
# Manual (if needed):
find src -type d -name codemind
git mv src/main/java/com/hackathon/codemind src/main/java/com/codemind
git mv src/test/java/com/hackathon/codemind src/test/java/com/codemind
# Then find/replace `com.hackathon.codemind` → `com.codemind` across the codebase.
```

**Verify:** `./mvnw clean compile` succeeds; `grep -r 'com.hackathon.codemind' src/` returns nothing.

### Step A2 — Downgrade Spring Boot + Spring AI
**Owner:** Dev 1 · **Effort:** 30 min · **File:** `pom.xml`

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.1</version>   <!-- or latest 3.4.x patch -->
</parent>
<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.0.0</spring-ai.version>   <!-- or latest 1.0.x GA -->
</properties>
```

Add (and remove anything 2.0-M-only) until `./mvnw clean compile` passes. Note: `spring-ai-starter-model-openai` artifact name is stable across 1.0/2.0; only the version coordinate changes.

### Step A3 — Rename env var
**Owner:** Dev 1 · **Effort:** 10 min · **Files:** `application.yaml`, `.env`, README

```yaml
spring:
  ai:
    openai:
      api-key: ${CODEMIND_AIHUB_KEY:}    # was OLLAMA_API_KEY
      base-url: https://ai-hub.f1arl.com
      chat:
        options:
          model: qwen3.5-35b
          temperature: 0.1
          max-tokens: 4096
      embedding:
        options:
          model: nomic-embed-text
```

Update `.env` and any developer-setup docs (`USER_GUIDE.md`) to reference `CODEMIND_AIHUB_KEY`.

### Step A4 — Add PgVector via docker-compose + starter
**Owner:** Dev 1 · **Effort:** 30 min · **Files:** new `docker-compose.yml`, `pom.xml`

**`docker-compose.yml`:**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: codemind
      POSTGRES_USER: codemind
      POSTGRES_PASSWORD: codemind
    ports: ["5432:5432"]
    volumes: [codemind_pgdata:/var/lib/postgresql/data]
volumes:
  codemind_pgdata:
```

**`pom.xml`** — add:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.liquibase</groupId>
    <artifactId>liquibase-core</artifactId>
</dependency>
```

Add to `application.yaml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/codemind
    username: codemind
    password: codemind
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml
  ai:
    vectorstore:
      pgvector:
        dimensions: 768
        distance-type: COSINE_DISTANCE
        index-type: HNSW
        initialize-schema: true
```

**Verify:** `docker-compose up -d`; `./mvnw spring-boot:run` starts and connects (look for the PgVector init log line).

### Step A5 — Delete fake embeddings, switch to real `nomic-embed-text` via hub
**Owner:** Dev 1 · **Effort:** 1 hr · **Files:** delete `retrieval/LocalEmbeddingService.java`; new `config/LlmConfig.java`

The hub is OpenAI-compatible, so Spring AI's `OpenAiEmbeddingModel` works as the `EmbeddingModel` bean once the embedding model is enabled. Flip `application.yaml`:
```yaml
spring:
  ai:
    openai:
      embedding:
        enabled: true       # was false in the skeleton
```

If the hub does **not** expose `/v1/embeddings`, fall back to a self-hosted Ollama just for embeddings:
```yaml
spring:
  ai:
    openai:
      embedding:
        base-url: http://localhost:11434/v1
        options:
          model: nomic-embed-text
```
(Spring AI 1.0 supports a per-feature `base-url`, so chat can stay on the hub.)

**Inside the code:** `InMemoryChunkStore` is replaced by `PgVectorStore` injection. The chunking logic in `IndexService` is preserved — only the store backend changes.

### Step A6 — Liquibase changelog
**Owner:** Dev 1 · **Effort:** 30 min · **File:** `src/main/resources/db/changelog/db.changelog-master.yaml`

Per ARCH §5 — two tables (`codemind_index_status`, `codemind_long_term_memory`). `vector_store` is auto-created by Spring AI's `initialize-schema: true`.

### Step A7 — Picocli subcommands + JLine REPL as fallback
**Owner:** Dev 5 · **Effort:** 1.5 hr · **Files:** new `cli/CodemindCli.java`, `cli/IndexCommand.java` (and placeholders for the rest); modify `CliRunner.java`

Add `info.picocli:picocli` + `info.picocli:picocli-spring-boot-starter` to `pom.xml`.

**`CodemindCli`** is the Picocli main, registering all subcommands. The existing `CliRunner` is invoked **only** when no subcommand is given (i.e., `java -jar codemind.jar` with no args) — that path enters the JLine REPL. For the demo, every command in the script is invoked as `codemind <subcommand>`.

Placeholders are fine for Day 0 — they just print "TODO: wired in Day 1 by Dev N". Real implementations land in Day 1.

### Step A8 — Extract `CodeSearchTool` from `ReActEngine`
**Owner:** Dev 1 · **Effort:** 30 min · **File:** new `tool/CodeSearchTool.java`

The semantic-search logic currently buried in `ReActEngine` becomes a `@Tool`-annotated `@SafeTool(SafetyLevel.SAFE)` component that queries `PgVectorStore.similaritySearch(...)`. `ReActEngine` itself will be deleted in Day 1 when `AgentExecutor` lands.

### Step A9 — Extend `SafetyLevel` + add `@SafeTool` annotation
**Owner:** Dev 1 · **Effort:** 15 min · **Files:** `policy/SafetyLevel.java`, new `policy/SafeTool.java`

```java
public enum SafetyLevel { SAFE, PREVIEW, CONFIRM_REQUIRED }

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeTool { SafetyLevel value(); }
```

`ApprovalService.approved(...)` already understands `CONFIRM_REQUIRED`. Add a no-op path for `SAFE` (just returns true). `PREVIEW` doesn't need an approval gate — `AgentExecutor` handles it.

### Day 0 exit criteria
- [ ] `./mvnw clean compile` passes
- [ ] `docker-compose up -d` starts PgVector; app connects on startup
- [ ] Startup banner prints `Using LLM endpoint: https://ai-hub.f1arl.com`
- [ ] `codemind --help` (Picocli) lists `index`, `ask`, `refactor`, `commit`, `changelog`, `do`, `rollback`
- [ ] `codemind --repl` (or `java -jar codemind.jar` with no args) drops into the existing JLine shell
- [ ] No source file under `com.hackathon.codemind` remains
- [ ] `policy/SafetyLevel` has three values; `policy/SafeTool` annotation exists

Push to `main`. Tag it `day-0-aligned`. **Day 1 starts here.**

---

## 3. Day 1 — Vertical Slice (`index` + `ask` end-to-end)

Refer to **PLAN.md §1 (Day 1)** for the full per-dev hour-by-hour schedule. Each dev's section there is now authoritative. Quick orientation:

| Dev | Day-1 deliverable (rough) |
|-----|---------------------------|
| Dev 1 | `LlmConfig`, `VectorStoreConfig`, `AgentConfig`, `CodeSearchTool` polish, `QnAAgent`, `DirectorAgent` |
| Dev 2 | `AgentExecutor` (real ReAct, PromptGuard + SafetyLevel integration, self-reflection), `AgentContext`, `AgentResult`, `ReActStep` |
| Dev 3 | `FileReaderTool`, `FileListTool`, `GrepTool`, `GitDiffTool`, `GitLogTool`, `AstAnalyzerTool` (start `WriteFileTool` and `GitCommitTool` if time) |
| Dev 4 | `LanguageDetector`, `ChunkMetadata`, `FileWalker`, language-aware `CodeChunker`, real `IndexingService` (PgVector-backed), all 7 `.st` prompts |
| Dev 5 | `CliOutputRenderer`, `PromptGuard`, `IndexCommand`, `AskCommand`, `DoCommand`, `RollbackCommand`, `ConversationMemoryService`, `LongTermMemoryService` |

**Day-1 gate:** `codemind index .` indexes a sample repo into PgVector; `codemind ask "Where is auth handled?"` returns a cited answer through the real ReAct loop with PromptGuard active.

---

## 4. Day 2 — Refactoring Apply + Commit/Changelog + Director Chain + Polish

Refer to **PLAN.md §2 (Day 2)** for full schedules. Quick orientation:

| Dev | Day-2 deliverable (rough) |
|-----|---------------------------|
| Dev 1 | `RefactorAgent` (analyze + applyPhaseOne), `ChangeAnalyzerAgent` (commit + changelog × 2 modes), Director chain integration |
| Dev 2 | Human-in-the-loop polish in executor, text-fallback hardening, reflection prompt tuning |
| Dev 3 | `WriteFileTool` + `GitCommitTool` + `MemoryStoreTool` + (optional) `ExecutePhaseTool`; tool robustness sweep on the demo repo |
| Dev 4 | `RefactorCommand` (incl. `--apply` flow), `CommitCommand`, `ChangelogCommand` (`--plain`), prompt tuning |
| Dev 5 | Output formatting polish, `--verbose` everywhere, full PromptGuard test, demo-rehearsal UX pass, backup video |

**Day-2 gate (Release Criteria):** PRD §6 (17 items). All must pass before the demo slot.

---

## 5. Demo Script (the 5 minutes you'll be judged on)

```bash
# Pre-demo (off-screen): export CODEMIND_AIHUB_KEY=...; docker-compose up -d; java -jar codemind.jar --version

$ codemind index .
# ~30s. Show the summary line: files / chunks / duration.

$ codemind ask "Where is rate limiting implemented?"
# Cited answer with file:line. Open one of the cited files briefly to prove the citation is accurate.

$ codemind refactor src/test/resources/sample-repo/MessyService.java
# Show: CRITICAL/WARNING/SUGGESTION tags, phased plan. Hit Y at the Phase-1 prompt.
# Show the per-file diff approval, hit Y on each.
# Show `git diff` after — the file is actually modified.

$ codemind commit
# Reads the staged change (the Phase-1 apply), proposes a Conventional Commits message.
# Hit Y. Show `git log -1`.

$ codemind changelog HEAD~3..HEAD
# Engineer-friendly grouped output.
$ codemind changelog HEAD~3..HEAD --plain
# Plain-English paragraph.

# Closing flourish (orchestration):
$ codemind do "where is auth handled and refactor the messy file"
# Director classifies, dispatches QnA first, then Refactor. Confirmation gate between.
```

Backup plan: **pre-recorded screencast** of the same script, kept on a USB stick. Play it if anything misbehaves live.

---

## 6. Risks & Hot Spots

| # | Risk | Mitigation |
|---|---|---|
| R1 | Hub doesn't expose `/v1/embeddings` for `nomic-embed-text` | Fall back to self-hosted Ollama for embeddings only (see A5). 1-line config. |
| R2 | Native tool calling unreliable on `qwen3.5-35b` via hub | Day-0 spike answers YES/NO; if NO, Dev 2 has text-fallback parser ready. |
| R3 | `WriteFileTool` corrupts a file mid-write on a demo box | Atomic write (`.tmp` + `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`); `.bak` taken first. |
| R4 | Director's strict-JSON output drifts | Low-confidence fallback to CLI menu; integration test with 10 sample inputs. |
| R5 | Demo box can't reach `ai-hub.f1arl.com` (corporate network quirk) | Test on the actual demo box ≥ 24 hrs before. Have a self-hosted Ollama fallback configured. |
| R6 | A `.st` prompt change breaks one agent shortly before demo | Lock `.st` files at start of Day-2 hour 6. After that: bug fixes only, not prompt tuning. |
| R7 | Phase-1 plan returned by model contains cross-file edits | RefactorAgent halts and reports; the user sees only the single-file Phase-1 portion applied. |
| R8 | A judge asks "but the proposal said 100% local" | Have the BRD §5 paragraph ready: org-internal vs. third-party cloud, privacy guarantee unchanged, self-hosted Ollama is a one-line config swap. Demonstrate the offline mode if asked. |

---

## 7. What we deliberately decided NOT to build

Document these in your demo Q&A prep so judges hear them as **decisions**, not gaps.

- **Docker SDK sandboxed execution.** Mentioned in proposal §04 / §06. Out of scope. Phase-1 writes happen against the host filesystem; safety comes from HITL + `.bak` + atomic write + rollback. Containerized execution is on the post-hackathon roadmap.
- **Tree-sitter chunking.** Proposal §05 mentions it. Out of scope. Language-aware regex chunking (Java/Python/JS) ships instead. Chunker has a clean interface; tree-sitter is a v2 swap.
- **ML-based prompt-injection classifier.** Regex `PromptGuard` covers public top-N patterns. Classifier is v2.
- **Phase 2 / Phase 3 auto-apply.** Cross-file mutations need higher trust + better impact analysis. Phase 1 is the right v0 scope.
- **IDE plugin, multi-repo, web dashboard, CI/CD integration.** All explicitly out of scope per BRD §8.

If a judge asks about any of these, the answer is: *"considered, deliberately scoped out, here's why, here's the roadmap."* That story is stronger than feature-creep.

---

## 8. Cross-references

| If you need… | Read… |
|---|---|
| Why a requirement exists | [BRD.md](BRD.md) |
| What the user-facing behaviour is, with test plans | [PRD.md](PRD.md) |
| How the components fit together | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Who does what hour by hour | [PLAN.md](PLAN.md) |
| Coding conventions every Claude session enforces | [../CLAUDE.md](../CLAUDE.md) (at project root so Claude Code auto-loads it) |
| How to drive the CLI as an end user | [../USER_GUIDE.md](../USER_GUIDE.md) (update at end of Day 1 to reflect Picocli subcommands + `CODEMIND_AIHUB_KEY`) |
