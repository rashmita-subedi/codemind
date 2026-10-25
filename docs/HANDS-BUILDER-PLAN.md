# Hands Builder — Coding Plan (Dev 3, Tools)

**Owner:** Sunaina · **Role:** Tool Developer ("the agent's hands")
**Scope:** 8 `@Tool` + `@SafeTool` components the agents call to touch the filesystem and git.

| # | Tool | Safety | Mutates? | Deps |
|---|------|--------|----------|------|
| 1 | `FileReaderTool`  | `SAFE`             | no  | none |
| 2 | `FileListTool`    | `SAFE`             | no  | none |
| 3 | `GrepTool`        | `SAFE`             | no  | none |
| 4 | `GitDiffTool`     | `PREVIEW`          | no  | git CLI |
| 5 | `GitLogTool`      | `SAFE`             | no  | git CLI |
| 6 | `AstAnalyzerTool` | `SAFE`             | no  | none |
| 7 | `WriteFileTool`   | `CONFIRM_REQUIRED` | **yes** | `ApprovalService`, `AuditService` |
| 8 | `GitCommitTool`   | `CONFIRM_REQUIRED` | **yes** | `ApprovalService`, `AuditService`, git CLI |

> `CodeSearchTool` already exists (Dev 1's, extracted from `ReActEngine`) and is the canonical reference for the pattern — do **not** rebuild it.

---

## Ground rules (from CLAUDE.md — non-negotiable)

- Package `com.codemind.tool`. Java 21, records for data, no Lombok, no comments except non-obvious *why*.
- **Every `@Tool` method returns `String`.** Parameters are `String`, `int`, or `boolean` only.
- **Every `@Tool` method's next line is `@SafeTool(SafetyLevel.X)`.** Startup scan fails fast if missing.
- No `try-catch` except at system boundaries — file I/O and `ProcessBuilder` *are* boundaries, so catch there and return an `ERROR: ...` string rather than throwing.
- Errors are returned as plain strings (e.g. `"ERROR: file not found: <path>"`), never thrown out of a `@Tool` method — the agent reads the string as an observation. Mirror `CodeSearchTool`'s `"ERROR: ..."` / `"No matching ..."` style.
- `@Component`, constructor injection only (no field injection, no Lombok).
- Tools are **pure Java where possible** (1–6). Only `WriteFileTool`/`GitCommitTool` take collaborators.
- Prompt sanitization is **not** the tool's job — `AgentExecutor` sanitizes tool args before dispatch (CLAUDE.md). Tools assume args are already guarded.

### ⚠️ Build-gate discrepancy to flag to Dev 1
CLAUDE.md and PLAN.md describe a JaCoCo **100% line+branch gate on `./mvnw verify`**, but `pom.xml` currently has **no JaCoCo plugin** — only `spring-boot-maven-plugin`. So `verify` will pass regardless of coverage today. Two options:
1. Ask Dev 1 (pom owner) to add the JaCoCo plugin block from PLAN.md §"Testing Strategy".
2. Until then, still write positive+negative tests per convention and self-check coverage in the IDE.
Do **not** edit `pom.xml` yourself — it's Dev 1's single-owner file.

---

## Shared scaffolding (build once, reuse across all 8)

Create small private helpers inside each tool (no shared util class — avoid premature abstraction per CLAUDE.md "no interfaces/classes for one impl"). The two recurring needs:

**A. Path safety / resolution** — resolve the user path, reject directory traversal outside the working dir if cheap to check, return `ERROR:` on non-existent paths. Used by 1,2,3,6,7.

**B. Git process runner** — used by 4,5,8. A private method per tool:
```java
private String runGit(Path repoDir, String... args) throws IOException, InterruptedException {
    var cmd = new ArrayList<String>(); cmd.add("git"); cmd.addAll(List.of(args));
    var pb = new ProcessBuilder(cmd).directory(repoDir.toFile()).redirectErrorStream(true);
    var p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }  // signal timeout
    return out;  // caller checks p.exitValue() if needed
}
```
`ProcessBuilder` for git is allowed in `PREVIEW`/`CONFIRM_REQUIRED` tools per CLAUDE.md (the "do NOT call ProcessBuilder outside CONFIRM_REQUIRED" rule is about *mutating* side effects; read-only git diff/log are gated at `PREVIEW`/`SAFE` and don't mutate — keep them read-only).

---

## Per-tool specifications

### 1. FileReaderTool — `SAFE`
```java
@Tool(description = "Read a file's content with line numbers. Optional 1-based line range.")
@SafeTool(SafetyLevel.SAFE)
public String readFile(String filePath, int startLine, int endLine)
```
- `startLine==0 && endLine==0` → whole file. Otherwise clamp to `[1, lineCount]`, `startLine>endLine` → swap or error.
- Prefix each line with right-aligned line number (`%6d\t`).
- **500 KB guard:** if `Files.size > 512_000` → `ERROR: file too large (N bytes); use a line range or GrepTool`.
- **Binary guard:** sample first ~8 KB; if it contains a `0x00` byte → `ERROR: binary file, not shown`.
- Negative cases: missing file, directory passed as file, range out of bounds, large file, binary file.

### 2. FileListTool — `SAFE`
```java
@Tool(description = "List a directory as an indented tree up to a max depth.")
@SafeTool(SafetyLevel.SAFE)
public String listFiles(String dirPath, int maxDepth)
```
- `maxDepth<=0` → default 3; clamp to e.g. `[1,10]`.
- Skip dirs: `.git`, `node_modules`, `target`, `build`, `.idea`, `dist`, `.codemind`.
- Indent by depth; mark dirs with trailing `/`. Cap total entries (e.g. 500) → append `... [truncated]`.
- Negative cases: path not a directory, missing path, empty dir, depth honored, skip-list honored.

### 3. GrepTool — `SAFE`
```java
@Tool(description = "Regex search across files under a path. Returns file:line: matchedLine, capped at 50 hits.")
@SafeTool(SafetyLevel.SAFE)
public String grep(String pathOrDir, String regex)
```
- Compile regex in a `try` → invalid pattern returns `ERROR: invalid regex: <msg>` (boundary catch is fine here).
- Walk files (reuse FileListTool skip-list); skip binary files (same `0x00` sniff); cap at **50 matches**, then `... [50-match cap reached]`.
- Output `relativePath:lineNo: trimmedLine`.
- Negative cases: invalid regex, no matches, binary skipped, cap enforced.

### 4. GitDiffTool — `PREVIEW`
```java
@Tool(description = "Show a git diff: unstaged, staged, or a commit range.")
@SafeTool(SafetyLevel.PREVIEW)
public String diff(String repoPath, String mode, String range)
```
- `mode`: `"unstaged"` → `git diff`; `"staged"` → `git diff --staged`; `"range"` → `git diff <range>` (validate `range` non-blank, reject shell metacharacters — pass as a single arg, never via shell).
- Not a git repo / git missing → `ERROR: ...`. Empty diff → `"No changes."`.
- `PREVIEW` because output must be shown to the human before downstream use (executor renders it + injects ack).
- Negative cases: unknown mode, blank range for range mode, non-repo dir, git-not-installed.

### 5. GitLogTool — `SAFE`
```java
@Tool(description = "Show recent commit history. limit caps the number of commits; optional range.")
@SafeTool(SafetyLevel.SAFE)
public String log(String repoPath, int limit, String range)
```
- `git log --oneline --decorate -n <limit>` (clamp limit `[1,200]`, default 20); append `range` arg if non-blank.
- Negative cases: non-repo, empty repo (no commits), limit clamping, bad range.

### 6. AstAnalyzerTool — `SAFE`
```java
@Tool(description = "Extract class/function signatures from a Java, Python, or JS/TS file via regex.")
@SafeTool(SafetyLevel.SAFE)
public String analyze(String filePath)
```
- Detect language by extension (`.java`/`.py`/`.js`/`.ts`). Unsupported → `ERROR: unsupported language: <ext>`.
- Per-language regex for class + method/function declarations; output `kind name (line N)`.
- Reuse the 500 KB / binary guards from FileReaderTool semantics.
- Negative cases: unsupported ext, missing file, file with no declarations, large file.

### 7. WriteFileTool — `CONFIRM_REQUIRED` ⚠️ mutating
```java
@Component
public class WriteFileTool {
    private final ApprovalService approvals;
    private final AuditService audit;
    // constructor injection

    @Tool(description = "Write content to a file. Creates a .codemind/backup/ backup first. Atomic.")
    @SafeTool(SafetyLevel.CONFIRM_REQUIRED)
    public String writeFile(String filePath, String newContent)
}
```
Flow (follow CLAUDE.md's `WriteFileTool` template exactly):
1. Build `diffPreview(filePath, newContent)` (old vs new, or "(new file)").
2. `var req = new ActionRequest("write_file:" + filePath, CONFIRM_REQUIRED, preview);`
3. `if (!approvals.approved(req)) { audit.record("write_file.rejected", filePath); return "User declined the write to " + filePath; }`
4. `backup(filePath)` → copy existing file to `.codemind/backup/<name>.<timestamp>.bak` (skip if file is new).
5. `atomicWrite`: write to `<file>.tmp` then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` (fall back to `REPLACE_EXISTING` only if `ATOMIC_MOVE` unsupported).
6. `audit.record("write_file.applied", filePath);` return byte count + backup location.
- Negative cases (**both branches mandatory**): mock `approvals.approved` → `false` (declined, no write, audit rejected) **and** `true` (write happens, backup created, audit applied); unwritable target dir; new-file path (no backup).

### 8. GitCommitTool — `CONFIRM_REQUIRED` ⚠️ mutating
```java
@Tool(description = "Commit currently staged changes with a message. Returns the new commit hash.")
@SafeTool(SafetyLevel.CONFIRM_REQUIRED)
public String commit(String repoPath, String message)
```
- Build preview = `git diff --staged --stat` (or the message + staged file list). Reject blank message → `ERROR: empty commit message`.
- Route through `ApprovalService` exactly like WriteFileTool. Declined → audit `git_commit.rejected`, return decline string.
- On approval: `git commit -m <message>` (message as a single arg — never interpolate into a shell string). Then `git rev-parse --short HEAD` → return hash. Audit `git_commit.applied`.
- Nothing staged → `git commit` exits non-zero → return `ERROR: nothing staged to commit`.
- Negative cases: blank message, declined approval, nothing staged, non-repo dir.

---

## Build order (dependency-aware, ~matches PLAN Day-1 Hour 1–6)

Pure, zero-dependency tools first (unblock yourself + agents immediately), gated/git tools last:

1. **FileReaderTool** → test → commit
2. **FileListTool** → test → commit
3. **GrepTool** → test → commit
4. **GitLogTool** → test (needs temp git repo) → commit
5. **GitDiffTool** → test → commit
6. **AstAnalyzerTool** → test → commit
7. **WriteFileTool** → test (mock ApprovalService both branches) → commit
8. **GitCommitTool** → test → commit

Each tool is "done" only when its `{ClassName}Test.java` exists with positive **and** negative cases and passes.

---

## Testing plan (mirror package `src/test/java/com/codemind/tool/`)

- **JUnit 5 + AssertJ + Mockito** (all from `spring-boot-starter-test`).
- **Pure I/O tools (1,2,3,6):** `@TempDir Path tempDir` — write real files, run the tool, assert on the returned string. No mocking.
- **Git tools (4,5,8):** init a real repo in `@TempDir`:
  ```java
  runGit(tempDir, "init"); runGit(tempDir, "config", "user.email", "t@t");
  runGit(tempDir, "config", "user.name", "t"); // then add/commit fixtures
  ```
  These need `git` on `PATH`. If CI lacks git, tag with `@Tag("integration")` and exclude via `-DexcludedGroups=integration` (per CLAUDE.md), but prefer keeping them as unit tests since git is present locally.
- **CONFIRM_REQUIRED tools (7,8):** `@Mock ApprovalService approvals; @Mock AuditService audit;` — stub `approved(...)` to return **both** `true` and `false`; verify `audit.record(...)` calls with `Mockito.verify`.
- Sample fixtures: reuse `src/test/resources/sample-repo/` where useful.
- **Coverage:** target 100% line+branch per convention. Run `./mvnw test` before pushing; run `./mvnw verify` once Dev 1 wires JaCoCo (see discrepancy note above). Every `ERROR:` branch needs a test that triggers it.

---

## Integration / wiring checklist

- [ ] All 8 are `@Component` so Spring picks them up.
- [ ] Each `@Tool` method has its `@SafeTool` — boot the app (`./mvnw spring-boot:run`) and confirm the startup `@SafeTool` scan passes (no `IllegalStateException`).
- [ ] Dev 1 registers the 8 `ToolCallback`s into the agent `ChatClient` beans in `AgentConfig` — coordinate so the tools are actually reachable by the ReAct loop. (Wiring is Dev 1's file; just confirm they're picked up.)
- [ ] `RollbackCommand` (Dev 5) reads from `.codemind/backup/` — agree the exact backup path/naming with Dev 5 so rollback can find WriteFileTool's `.bak` files.

## Coordination / open questions to raise at standup
1. **JaCoCo gate missing from `pom.xml`** — ask Dev 1 to add it.
2. **Backup path contract** with Dev 5 (`.codemind/backup/<name>.<ts>.bak`?) for RollbackCommand.
3. Confirm `ApprovalService` is the only approval path (it is, per `ActionRequest`/`approved`) — `SAFE` short-circuits to `true`, `AUTO` mode auto-approves; tests must not assume interactive stdin.
