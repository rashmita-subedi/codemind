# Claude Code Tips for Team CodeMind

## Getting Started

### Installation
Claude Code is already available via Claude Max subscription. Open a terminal and run:
```
claude
```

### Resuming Sessions
| Command | What it does |
|---------|-------------|
| `claude` | Start a new conversation |
| `claude --continue` | Resume your most recent conversation (keeps full context) |
| `claude --resume` | Pick from a list of recent conversations |

Use `--continue` when you step away and come back. Your full conversation history is preserved.

## Essential Commands (Inside a Session)

### Navigation
| Command | What it does |
|---------|-------------|
| `/help` | Show all available commands |
| `/clear` | Clear conversation history (fresh start) |
| `/compact` | Compress conversation to save context space |
| `/plan` | Enter plan mode (Claude can only read, not edit — good for design) |
| `/config` | Open settings menu |

### Keyboard Shortcuts
| Shortcut | Action |
|----------|--------|
| `Enter` | Send message |
| `Shift+Enter` | New line (don't send yet) |
| `Shift+Tab` | Toggle between send and newline mode |
| `Esc` | Cancel current generation |
| `Ctrl+C` | Exit Claude Code |

### Running Shell Commands
Type `! <command>` to run a shell command inline:
```
! docker-compose up -d
! mvn clean compile
! git status
```
The output goes directly into the conversation context, so Claude can react to it.

## CLAUDE.md — The Most Important File

`CLAUDE.md` in your project root is **automatically loaded** at the start of every Claude Code session. It contains project conventions, patterns, and rules that Claude follows.

**For our project:** The `CLAUDE.md` file is in `docs/CLAUDE.md`. During implementation, Dev 1 will copy it to the project root. Once it's there, every team member's Claude session will follow the same conventions automatically.

**Why this matters:** Without CLAUDE.md, each person's Claude might generate different coding styles, use different patterns, or add unwanted dependencies. With it, everyone's Claude produces consistent code.

## How to Work Effectively with Claude Code

### 1. Be Specific About What You Want
Bad: "Write the tool"
Good: "Write FileReaderTool.java in com.codemind.tool package. It should be a @Component with a @Tool method that reads a file and returns its contents with line numbers. Follow the tool pattern in CLAUDE.md."

### 2. Give Context About Dependencies
Bad: "Write IndexingService"
Good: "Write IndexingService.java. It depends on FileWalker (already written), CodeChunker (already written), and VectorStore (Spring AI bean from config). It should walk files, chunk them, and batch-add to VectorStore."

### 3. Ask Claude to Read Files First
"Read FileReaderTool.java and then write GrepTool.java following the same pattern."
This ensures consistency without you having to describe the pattern.

### 4. Use Plan Mode for Design
Type `/plan` before asking Claude to design something complex. In plan mode, Claude will research and think without making any changes. Type `/plan` again to exit and start implementing.

### 5. Let Claude Run Tests
"Run the tests for CodeChunker and fix any failures."
Claude can execute commands, see output, and iterate.

**Standing rule:** after every feature or class, ask Claude to generate JUnit tests with **100% coverage** and **both positive and negative cases**, then run `./mvnw verify` to enforce the coverage gate. A class isn't done until its tests pass and coverage is 100%. Good prompt:
> "Write JUnit 5 tests for `<ClassName>` with 100% line + branch coverage — both positive and negative cases (happy path, error paths, edge cases, guardrails). Then run `./mvnw verify` and fix any failures or coverage gaps."

### 6. Use Compact When Context Gets Long
If you've been working for a while and Claude seems to "forget" earlier context, type `/compact`. This summarizes the conversation to free up space.

## Team Workflow with Claude Code

### Each Developer's Setup
1. Clone the repo: `git clone https://github.com/NischalPh/codemind.git`
2. Switch to your branch: `git checkout -b dev-N/feature-name`
3. Open Claude Code in the project directory: `cd codemind && claude`
4. Claude automatically reads CLAUDE.md — you're ready to go

### When You're Blocked
If you're waiting for another dev's work (e.g., VectorStore config):
- Work on standalone components that have no dependencies
- Write JUnit tests for your existing code (100% coverage, positive + negative cases)
- Write stubs/placeholders that you'll wire up later

### Before Merging
1. Make sure your code compiles: `! mvn clean compile`
2. Run tests + coverage gate: `! mvn verify` (must be green at 100% coverage)
3. Push your branch: `! git push -u origin dev-N/feature-name`
4. Wait for the merge ceremony (fixed merge order)

## Useful Claude Code Patterns

### "Implement this class following the pattern in X"
Claude will read file X and create a consistent implementation.

### "Read all the tools in src/main/java/com/codemind/tool/ and tell me if they follow the same pattern"
Great for catching inconsistencies before merge.

### "Run docker-compose up -d and verify the database is accessible"
Claude can interact with infrastructure.

### "Read the error log and fix the issue"
Paste or let Claude read error output, and it will diagnose and fix.

### "Review my changes before I commit"
Claude will look at git diff and catch issues.
