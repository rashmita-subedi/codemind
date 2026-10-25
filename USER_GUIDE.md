# CodeMind User Guide

CodeMind is a repo-aware AI assistant you run from your terminal. Point it at a
codebase and it can answer questions about the code, explain end-to-end API
flows, propose and apply refactors, and draft commit messages and changelogs —
all grounded in the actual files it has indexed.

There are two ways to use it:

- **Subcommands** — one-shot commands like `codemind ask "..."` or `codemind commit`. Best for scripting and quick tasks.
- **Interactive shell (REPL)** — launch with no arguments and chat with the assistant. Best for exploring a repo.

---

## Quick start

```bash
# 1. Build
mvn clean package -DskipTests

# 2. Start the vector store + embeddings (Docker)
docker compose up -d chroma ollama ollama-pull

# 3a. Run a one-shot command
java -jar target/codemind.jar ask "what does the login flow do?"

# 3b. …or open the interactive shell
java -jar target/codemind.jar
```

On startup the interactive shell automatically indexes the directory you launched
it from, so open your terminal in the repo (or parent workspace) you want analyzed
*before* you run it.

> **No Docker?** Set `CODEMIND_VECTOR_STORE=memory` to use the in-process fallback
> store (hash-based embeddings — functional, lower recall, no Chroma/Ollama needed).

---

## Prerequisites

- **Java 21+**
- **Maven** (use your system `mvn`)
- **Docker** for the Chroma vector store + Ollama embeddings — *or* `CODEMIND_VECTOR_STORE=memory` to skip it
- **API key** for the chat model, exported as `CODEMIND_AIHUB_KEY` (falls back to the default key in `application.yaml` if unset)

---

## Commands

Run any of these as `java -jar target/codemind.jar <command>`, or type them at the
shell prompt (without the `java -jar …` part).

| Command | What it does |
|---|---|
| `index [path]` | Index a repo for semantic search. Defaults to the current directory. Add `--force` to re-index unchanged files. |
| `ask "<question>"` | Ask a natural-language question about the indexed code. Add `--verbose` to see the reasoning trace. |
| `refactor <path>` | Analyse a file/folder and propose a phased refactor plan. Add `--apply` to apply Phase-1 safe extractions, `--verbose` for the full trace. |
| `commit` | Auto-stage, draft a Conventional Commits message from the diff, then create the commit (after confirmation). |
| `changelog [A] [B]` | Draft a changelog from a git revision range. `A B` becomes `A..B`; a single `A` becomes `A..HEAD`; no args defaults to `HEAD~1..HEAD`. Add `--plain` for a plain-English paragraph. |

Two more commands exist but are hidden (advanced / not user-facing yet):
`do "<free-form request>"` routes any request to the right specialist automatically,
and `rollback` (placeholder) will restore files from a previous `refactor --apply`.

### `index`

```bash
codemind index                       # index the current directory
codemind index "D:\workspace\bank-service"
codemind index --force .             # re-index everything, ignore the cache
```

For a fleet of microservices, index the **parent** workspace so cross-service
questions work:

```bash
codemind index "D:\workspace\Central Services"
```

**The indexed repo is also the active workspace** for all git-aware commands.
After `codemind index D:\peer-service`, a subsequent `commit` / `changelog` /
`ask` in the same session targets that repo, regardless of which directory
you launched CodeMind from.

### `ask`

```bash
codemind ask "list all REST endpoints grouped by module"
codemind ask "explain the customer onboarding flow end to end" --verbose
```

### `refactor`

```bash
codemind refactor src/main/java/com/acme/OrderService.java          # analyse only
codemind refactor src/main/java/com/acme/OrderService.java --apply   # analyse, then apply Phase 1
```

In **ask** mode, the flow is:

1. The analyse turn prints the proposed Phase-1 plan.
2. `Apply Phase-1 refactor to <path>? [y/N]` — you confirm at the plan level.
3. For each file the apply step touches, you also confirm the individual
   `write_file` (the per-file diff is shown first).
4. Each rewritten file is backed up to `<repo>/.codemind/backup/` before being replaced.

In **agentic** mode, both confirmations are auto-approved and the apply runs end-to-end.

### `commit`

`commit` performs the whole flow end-to-end:

1. **Auto-stage.** If nothing is staged, runs `git add -A`. If you've already
   pre-staged a partial change, your staging is left alone.
2. **Draft.** Reads the staged diff and drafts a **Conventional Commits**
   message (`feat(cli): …`, `fix(agent): …`, etc.).
3. **Confirm.** Shows the message and asks `Apply this commit? [y/N]`
   (auto-yes in agentic mode).
4. **Commit.** Runs `git commit -F <tempfile>` with the cleaned message.

```bash
codemind commit
```

If the working tree is clean (or you're not in a git repo), it prints
*"Nothing to commit"* and stops without calling the LLM. It also refuses to
commit if the model returned an error string instead of a real message.

### `changelog`

```bash
codemind changelog                       # HEAD~1..HEAD, grouped by type
codemind changelog HEAD~5..HEAD          # explicit range form
codemind changelog v1.2.0..HEAD          # explicit range form
codemind changelog v0.4.8 v0.4.9         # two-arg form → v0.4.8..v0.4.9
codemind changelog v1.0.0                # single-rev form → v1.0.0..HEAD
codemind changelog v1.2.0..HEAD --plain  # plain-English paragraph
```

The default grouped output sorts changes into Features / Fixes / Refactors /
Perf / Other based on Conventional-Commit types; `--plain` produces a narrative
summary instead.

---

## Interactive shell (REPL)

Launch with no arguments:

```bash
java -jar target/codemind.jar
```

You'll get a prompt that shows the current mode:

```text
  codemind  ask  >
```

Just type a question — you don't need to prefix it with `ask`:

```text
  codemind  ask  >  where is the JWT token validated?
```

### Modes

CodeMind has **one** mode switch with two settings. (The earlier two-axis
`ask|agentic` + `manual|auto` system has been removed — there is no separate
"execution mode" anymore.)

| Mode | Behavior |
|---|---|
| **ask** (default) | Q&A and analysis. Destructive actions (commit, refactor apply) **ask for confirmation** first. |
| **agentic** | Deeper cross-file reasoning, and destructive actions **auto-apply** without prompting. |

Switch between them:

- `/a` or `/mode` — toggle ask ↔ agentic
- `/ask` — force ask mode
- `/agentic` — force agentic mode
- `Alt+A` — keyboard toggle

```text
  codemind  ask  >  /a
  mode: agentic
  codemind  agentic  >  explain the full payment flow across all services
```

### Shell built-ins

| Input | Action |
|---|---|
| `index [path]` | re-index (current dir by default) |
| `ask <question>` | optional; in the REPL you can just type the question directly |
| `commit` | auto-stage + draft + commit (asks first in ask mode, auto-applies in agentic) |
| `changelog [A] [B] [--plain]` | draft a changelog; same range semantics as the subcommand |
| `refactor <path>` | plan + apply a refactor (asks at plan level + per file in ask mode) |
| `clear` | clear screen and reset to ask mode; re-indexes only if the active repo differs from the current directory |
| `help` | list commands |
| `exit` / `quit` | close CodeMind |
| `!<command>` | run a raw shell command in the project directory (e.g. `!git status`) |

Short, well-known shell commands (like `git status`, `ls`, `docker ps`) are
detected and run directly even without the `!` prefix. Inputs that are mostly
punctuation (`//`, `--`, `?`, `..`) are silently treated as noise — they don't
trigger an LLM round-trip. Anything that looks like a real question is sent to
the assistant.

### `clear` in detail

`clear` is a session reset:

1. Clears the terminal scrollback (ANSI screen wipe).
2. Resets the conversation mode to **ask**.
3. If the active indexed repo is **already** the current working directory, that's
   it — instant clean slate.
4. If the active repo points elsewhere (you ran `index <other-path>` earlier),
   `clear` asks `Continue?` and then re-indexes the current directory.
5. Reprints the banner.

### Shell UX tips

- **Tab** completes commands and modes.
- **Up / Down** browse input history (persisted to `~/.codemind_history`).
- **Ctrl+C** cancels the in-flight operation and returns to the prompt — it does
  **not** close the app. Use `exit` or `quit` to close.
- Output is rendered with terminal colour, markdown styling, syntax-aware code
  blocks, and underlined `file.java:42` references that you can click in modern
  terminals (Windows Terminal, iTerm2, etc.).

> If Up/Down doesn't work inside IntelliJ's plain Run console, enable terminal
> emulation for that run configuration, or run CodeMind from Windows Terminal /
> PowerShell. The plain console doesn't always forward arrow-key escape sequences.

---

## Models & vector store

CodeMind splits chat and embedding traffic across two backends:

- **Chat model** — org OpenAI-compatible endpoint (`qwen3.5-35b` on `https://ai-hub.f1arl.com`). Your `CODEMIND_AIHUB_KEY` only permits this model.
- **Embeddings** — Ollama in Docker. Default `all-minilm` (384-dim, fast on CPU, with `truncate: true` so oversize chunks are silently shortened to the model window instead of erroring). Swap to `nomic-embed-text` (768-dim, better recall, slower) via `OLLAMA_EMBED_MODEL=nomic-embed-text`.
- **Vector store** — Chroma in Docker.

If you change `OLLAMA_EMBED_MODEL` you **must re-index** — collections are pinned
to the original embedding dimension. Delete `<repo>/.codemind` and the Chroma
`codemind` collection, then `index <path>` again.

### Configuration knobs

All optional — defaults match `docker-compose.yml`:

| Variable | Default | Purpose |
|---|---|---|
| `CODEMIND_AIHUB_KEY` | (yaml default) | Chat model API key |
| `CODEMIND_VECTOR_STORE` | `chroma` | `memory` bypasses Chroma + Ollama |
| `OLLAMA_EMBED_MODEL` | `all-minilm` | Embedding model (re-index after changing) |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `CHROMA_HOST` / `CHROMA_PORT` | `http://localhost` / `8000` | Chroma endpoint |
| `CHROMA_COLLECTION` | `codemind` | Chroma collection name |
| `CHROMA_TENANT` / `CHROMA_DATABASE` | `default_tenant` / `default_database` | Chroma namespace |

Indexing tuning (in `application.yaml`):

- `codemind.embed.parallelism` (default 8) — concurrent batches to Ollama.
- `codemind.embed.batch-size` (default 16) — chunks per batch.
- `codemind.patterns.max-file-size-bytes` (default 1,000,000) — skip large/generated files.
- `codemind.patterns.*` — override language/framework classification keywords for your stack.

Internal: chunks are 30 lines each (kept small so they fit `all-minilm`'s 256-token
window without truncation). Indexing skips `/test/`, `/tests/`, `/__tests__/`,
`/spec/`, `/generated/`, `/static/`, `/public/`, `/assets/`, `/.mvn/`, build
artefacts, and any file above the size cap.

### Local caches

```text
<repo>/.codemind/chunks.bin   # Chroma mode: chunk text/metadata (vectors live in Chroma)
<repo>/.codemind/index.bin    # memory mode: chunks + local embedding vectors
<repo>/.codemind/backup/      # refactor apply backups (used by rollback when wired)
```

The `.codemind` folder should be added to `.gitignore` for repos you index — it's
session-local state, not source.

---

## Troubleshooting

**No useful answer**
- Make sure you indexed the right repo/workspace (`index <path>`).
- For broad cross-file questions, switch to `agentic` mode.
- Indexing failures now tell you whether the path is missing, not a directory, or unreadable.

**Model call / config failures**
- Confirm `CODEMIND_AIHUB_KEY` is set in the same terminal.
- Verify the endpoint URL and model name in `application.yaml`.

**Chroma / vector store**
- Startup connection error → ensure `docker compose up -d chroma` is running and reachable at `${CHROMA_HOST}:${CHROMA_PORT}`.
- No results after a Docker volume wipe → re-run `index <path>` to repopulate the collection.
- To skip Chroma entirely → restart with `CODEMIND_VECTOR_STORE=memory`.

**Ollama / embeddings**
- `connection refused` to `localhost:11434` → Ollama container isn't up. `docker compose up -d ollama`, then `docker exec codemind-ollama ollama list` to confirm.
- `model not found` → the one-shot pull failed: `docker exec codemind-ollama ollama pull all-minilm`.
- First index pass is slow → Ollama loads the model into memory on the first request; subsequent embeddings are fast.
- `input length exceeds the context length` → your chunks are bigger than the model window. Either lower `CHUNK_SIZE_LINES` (currently 30) or keep the default; `truncate: true` on the embedding options will silently trim instead of failing.

**`commit` did nothing**
- "Nothing to commit" means the working tree is clean or you're not in a git repo.
- If it refused after drafting, the model returned an error string instead of a message — re-run, or check the staged diff is meaningful.
- The commit runs against the **active indexed workspace**, not the JVM launch directory. If `git log` doesn't show the new commit, check `git -C <indexed-path> log`.

**`changelog` produced an empty or wrong-looking result**
- Make sure both refs exist locally (`git rev-parse <ref>`).
- For a span between two specific refs, pass them as `changelog A B` (two args)
  or `A..B`. A single rev like `changelog v1.0.0` is interpreted as `v1.0.0..HEAD`.

**`refactor --apply` ran but nothing changed on disk**
- In **ask** mode you must approve both the plan AND each per-file `write_file`.
  If you `y` the plan but `n` the per-file prompt, no files change.
- Check `<repo>/.codemind/backup/` — if there are recent `.bak` files there,
  the apply did run; the writes may have completed.
- Switch to **agentic** mode (`/a`) and re-run if you want to skip the per-file prompts.

**`clear` is asking for confirmation even though I'm already in the indexed repo**
- `clear` only skips the prompt when the active indexed path matches the current
  working directory exactly. If you've cd'd to a subdirectory after launch, the
  paths won't match — that's the prompt firing.

**Confirmation prompts**
- Destructive actions prompt in **ask** mode and auto-apply in **agentic** mode. Switch with `/a` if you want the opposite behavior.
