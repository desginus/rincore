---
name: coding-agent
description: Lightweight coding agent capability. Guides the assistant to plan, implement, test, and iterate on code using existing workspace tools. Suitable for basic development workflows.
---

# Coding Agent

Use this skill when the user asks for programming, code editing, debugging, or development tasks.
It converts the general assistant into a lightweight coding agent using existing tools.

## Core Workflow

Every coding task follows this loop:
1. **Understand** — Read the relevant files, understand the codebase structure
2. **Plan** — Outline what needs to change before writing code
3. **Implement** — Use workspace tools to edit files
4. **Verify** — Run build/compile/test commands and check output
5. **Fix** — If verification fails, diagnose and iterate

## File Editing Rules

- Read a file BEFORE editing it. Don't guess its content.
- Use `workspace_read_file` to read, `workspace_edit_file` for precise replacements, `workspace_write_file` for new files.
- Make focused, minimal changes. Don't reformat existing code.
- For search/replace edits in `workspace_edit_file`, use enough context around the target to make the match unique.

## Git Discipline

- Before any set of changes: check `git status`, `git diff`, `git log --oneline -5` to understand the state.
- After implementing: `git diff` to review your changes before committing.
- Commit with clear, conventional messages: `type(scope): brief description`
- If the user didn't specify otherwise, commit after each logical unit of work.

## Testing & Verification

- After writing code, always run the relevant build/test command.
- For Python: `python3 -m pytest` or `python3 -c "import <module>"`
- For Kotlin/Java: check if Gradle/Maven is configured, run the appropriate test task
- For JavaScript/TypeScript: check package.json for test/build scripts
- If a test fails, read the error message and fix it. Don't give up on the first failure.

## Error Handling

- If a command fails, read the error output carefully before deciding what to fix.
- Prefer running commands that offer helpful diagnostics: `--help`, `--version`
- If dependencies are missing, install them with the appropriate package manager.
- Only escalate to the user if you've exhausted reasonable fix attempts.

## When NOT to use this skill

- General conversation not related to programming
- Questions that don't require reading or writing code
- Tasks better handled by dedicated IDEs or specialized coding agents
