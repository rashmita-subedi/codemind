                                                                                                                                                                                                                                                                                                                                                                                    # Memory Management

Session-scoped conversation memory for CodeMind. This document covers the architecture,
the data structure, why it exists, and the design decisions behind it.

## Problem it solves

Before this, every agent call constructed a fresh `new AgentContext(UUID.randomUUID()…)`,
so nothing survived between turns. There was no `memory/` package and no retention across
turns or sessions — only the per-run message list acted as working memory. Follow-up
questions ("now refactor **it**") had zero knowledge of the previous turn.

This adds **session-wise** memory: a stable session id plus a bounded sliding window of
recent turns, shared by both conversational entry points.

## 1. Where memory lives in the architecture

```
                          ┌──────────────────────── CLI / REPL ────────────────────────┐
                          │  CliRunner (REPL)        DoCommand / AskCommand (picocli)   │
                          │      │  free text             │  one-shot subcommand        │
                          └──────┼────────────────────────┼─────────────────────────────┘
                                 │                        │
                  ask(question)  ▼                        ▼  dispatch(input)
                          ┌─────────────┐          ┌──────────────┐
                          │  QaService  │          │ DirectorAgent│  (classify → route)
                          └──────┬──────┘          └──────┬───────┘
                                 │                        │
                                 ▼                        ▼
                          ┌─────────────┐     ┌──────────────────────────────┐
                          │ ReActEngine │     │ QnAAgent / RefactorAgent /   │
                          │ (RAG path)  │     │ ChangeAnalyzerAgent          │
                          └──────┬──────┘     └──────────────┬───────────────┘
                                 │                           │ execute(ctx)
                                 │                           ▼
                                 │                    ┌──────────────┐
                                 │                    │ AgentExecutor│ (ReAct loop)
                                 │                    └──────┬───────┘
                                 │                           │
              ╔══════════════════▼═══════════════════════════▼══════════════════╗
              ║                       memory/  (NEW)                             ║
              ║                                                                  ║
              ║   ┌────────────────┐         ┌──────────────────────────────┐   ║
              ║   │ SessionManager │◄────────│ ConversationMemoryService    │   ║
              ║   │  currentId()   │ which   │  record / recordExchange     │   ║
              ║   │  reset()       │ session?│  messages() / transcript()   │   ║
              ║   └────────────────┘         │  history / turnCount / clear │   ║
              ║                              └──────────────┬───────────────┘   ║
              ║                                             │ sanitize-on-store ║
              ║                                             ▼                   ║
              ║                                      ┌──────────────┐           ║
              ║                                      │ PromptGuard  │           ║
              ║                                      └──────────────┘           ║
              ╚══════════════════════════════════════════════════════════════════╝
```

Two reader/writer entry points — `ReActEngine` (REPL free-text RAG path) and `AgentExecutor`
(the agent ReAct loop) — share **one** `ConversationMemoryService` and **one**
`SessionManager`, so an entire REPL session is a single coherent memory.

### Components

| Class | Role |
|---|---|
| `SessionManager` | Holds the id of the conversation the CLI is currently inside. `reset()` rotates it when the session is cleared. Replaces per-call random UUIDs. |
| `ConversationMemoryService` | The session window. Records turns, evicts by bounds, exposes `messages()` (Spring AI view) and `transcript()` (string view). Sanitizes on store. |
| `ConversationTurn` | Immutable record: `Role` (USER / ASSISTANT) + content, with `charCount()` used by budget eviction. |
| `SessionStore` | Append-only per-session transcript log on disk (`.codemind/sessions/<id>.jsonl`). Makes memory survive process restarts; best-effort (I/O failures degrade to in-memory-only). |
| `SessionLaunchOptions` | Parses the REPL launch flags `--continue` / `-c` / `--resume [id]` / `--session <id>`. |

## 2. The data structure: a dual-bounded sliding window

```
ConcurrentHashMap<sessionId, Window>          ← thread-safe, one window per session
                       │
                       ▼
   Window  (FIFO Deque + running char counter)
   ┌──────────────────────────────────────────────────────────────┐
   │  evict-oldest ◄── [U:"where is auth"] [A:"AuthSvc:42 …"] ...  │ ──► newest
   │                    └── dropped when EITHER bound trips ──┐     │
   │   bound 1: size  > maxTurns (20)   → recall depth        │     │
   │   bound 2: chars > maxChars (8000) → token/cost ceiling  │     │
   └──────────────────────────────────────────────────────────────┘
        chars counter updated on every add/remove  → O(1) amortized
```

**Why two bounds:** turn-count alone lets 20 huge turns explode the prompt
(cost / latency / context-overflow); char-budget alone lets 500 tiny turns bloat recall.
Together they cap both dimensions deterministically.

The per-session `Window` keeps a **running character total**, so eviction is O(1) amortized
per insert instead of re-summing the whole window on every turn.

## 3. Flow: why retention now works (before vs after)

```
   BEFORE                                  AFTER (session-wise)
   ─────────────────────────────          ─────────────────────────────────────────
   turn 1: ask("explain LoanSvc")         turn 1: sessionId = S (stable)
     ctx = new AgentContext(UUID-a)          seed = []           → answer A1
     → answer A1                             record(S, U:q1) record(S, A:A1)
     [discarded]
                                           turn 2: sessionId = S (same)
   turn 2: ask("now refactor it")            seed = [q1, A1]     → answer A2  ← knows "it"
     ctx = new AgentContext(UUID-b)          record(S, U:q2) record(S, A:A2)
     "it" = ??? no context  ✗
                                           exit → clear(S) + reset → new session ✓
```

Before, `UUID.randomUUID()` per call meant turn 2 had zero knowledge of turn 1 — the model
could not resolve "it". After, the stable session id + window means turn 2 is seeded with
turn 1, so pronouns and follow-ups resolve.

## 4. Why we need it / benefits

| Benefit | Mechanism | Concrete payoff |
|---|---|---|
| **Conversational continuity** | stable `sessionId` + seeded window | "now refactor **it**", "and the other one?" actually work |
| **Bounded cost & latency** | dual-bound sliding window | prompt size cannot grow unbounded across a long session |
| **No memory leak** | `clear()` on `exit` + per-session map | sessions do not accumulate forever in a long-running REPL |
| **Prompt-injection safe** | `sanitize()` on store | malicious tool / file output cannot smuggle instructions via memory |
| **Thread-safe** | `ConcurrentHashMap` + per-window lock | safe if the REPL ever drives concurrent calls |
| **Config-tunable** | `codemind.memory.window` / `max-chars` | trade recall vs. token spend without a rebuild |
| **Pluggable forward** | clean service API | a future `LongTermMemoryService` (PgVector) drops in beside it |

## 5. Why this is the best-quality version (and what was rejected)

| Decision | Chosen | Why it beats the alternative |
|---|---|---|
| Eviction cost | **running char counter (O(1))** | beats re-summing the whole window each insert (O(n)) |
| Bounding | **turns AND chars** | beats single-bound — caps both recall depth and token cost |
| Concurrency | **`ConcurrentHashMap` + per-window `synchronized`** | beats a single global lock (no cross-session contention) |
| Injection defense | **sanitize on the way *in*** | beats sanitize-on-read — stored data is safe once, not re-checked everywhere |
| Storage | **in-process per session** | matches the explicit "session-wise" choice; no DB dependency, no Docker |
| Integration point | **shared service in `AgentExecutor` + `ReActEngine`** | beats per-agent ad-hoc memory — one source of truth, both paths benefit |
| Lifecycle | **`exit` clears + rotates** | makes the existing "Session cleared." message truthful; bounds the session map |

### Honest trade-offs

- **Char-budget is a proxy for tokens.** A real tokenizer would be exact but drags in a
  heavy dependency for marginal gain.
- **Persistence is a flat per-session transcript log**, not a searchable store. It gives
  Claude-style resume (`--continue` / `--session`) cheaply. Semantic recall *across* many
  past sessions remains the job of a future `LongTermMemoryService` (PgVector).
- **The `.jsonl` log is append-only and unrotated** — fine for normal sessions; a very long
  session grows its file unbounded. Rotation/compaction can be added later if needed.

## 6. Cross-restart persistence (`--continue` / `--session`)

The in-memory window is **working memory** (fast, bounded). `SessionStore` is the
**durable log** behind it, so a conversation survives closing and reopening the app — the
same idea as Claude Code's `--continue` / `--resume`.

```
   write path (every turn)                 read path (startup resume)
   ───────────────────────                 ──────────────────────────
   record(S, turn)                         launch with --continue / --session <id>
     ├─ window.add(turn)   (RAM)             ├─ SessionManager.setCurrentSessionId(id)   (sanitised)
     └─ store.append(S, turn) ──┐           ├─ memory.resume(id)
                                 ▼           │     └─ store.load(id) → replay into a fresh window
              .codemind/sessions/<id>.jsonl │           (re-sanitised; window bounds re-applied)
              {"role":"USER","content":...} │
              {"role":"ASSISTANT",...}  ◄───┘     window now holds the recent tail of the log
```

Key properties:

- **Full log on disk, recent window in RAM.** The `.jsonl` file is the complete transcript;
  `resume()` replays it through the same turn/char bounds, so RAM holds only the recent tail.
- **Best-effort.** If the disk write fails, the live conversation continues from the in-memory
  window — persistence never breaks a turn.
- **Path-safe.** Session ids are normalised to `[A-Za-z0-9_-]` at adoption *and* validated in
  the store, so `--session ../../etc` cannot escape the sessions directory.
- **Re-sanitised on load.** A transcript file is treated as untrusted input on reload.

### Usage

```bash
codemind                       # fresh session
codemind --continue            # resume the most recently used session
codemind -c                    # same as --continue
codemind --session my-feature  # resume (or start) a named session
codemind --resume <id>         # resume a specific session id
```

Inside the REPL, `exit` ends the current session (kept on disk, resumable) and starts a new
one; `quit` closes the app. These flags are for the interactive REPL; one-shot subcommands
(`codemind ask "…"`) remain stateless per invocation.

## 7. Configuration

`application.yaml` (env-overridable):

```yaml
codemind:
  memory:
    window: ${CODEMIND_MEMORY_WINDOW:20}          # max turns retained per session
    max-chars: ${CODEMIND_MEMORY_MAX_CHARS:8000}  # max total characters per session
    dir: ${CODEMIND_MEMORY_DIR:.codemind/sessions} # transcript directory (blank = disable persistence)
```

## 8. Files

| Path | Change |
|---|---|
| `memory/ConversationMemoryService.java` | new — the session window + `resume()` / `latestSession()` |
| `memory/SessionManager.java` | new — stable session id holder + `setCurrentSessionId()` |
| `memory/ConversationTurn.java` | new — turn record + `Role` |
| `memory/SessionStore.java` | new — append-only JSONL persistence per session |
| `cli/SessionLaunchOptions.java` | new — parses `--continue` / `--session` / `--resume` |
| `agent/core/AgentExecutor.java` | seeds loop with prior turns, records exchange |
| `agent/ReActEngine.java` | weaves transcript into prompt, records exchange |
| `agent/QnAAgent / RefactorAgent / ChangeAnalyzerAgent.java` | use shared session id |
| `cli/CliRunner.java` | startup resume; `exit` ends session (kept on disk) + rotates |
| `CodemindApplication.java` | routes resume flags to the REPL instead of Picocli |
| `application.yaml` | `codemind.memory.*` settings |
