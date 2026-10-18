---
name: todoist-cli
description: Manage Todoist tasks from the command line. Use when the user asks to create, list, update or delete tasks.
version: 1.2.0
emoji: "✅"
metadata:
  requires:
    env:
      - name: TODOIST_API_KEY
        required: true
        description: Todoist API token used for authenticated requests.
    bins:
      - curl
      - jq
---

# Todoist CLI Skill

When the user asks to manage tasks, use the `todoist` CLI tool:

## Available Operations
1. **List tasks**: Run `todoist-cli list` to see all active tasks.
2. **Add task**: Run `todoist-cli add "task description" --priority P1`.
3. **Complete task**: Run `todoist-cli close <task-id>`.

## Priority Levels
- P1: Urgent
- P2: High
- P3: Medium
- P4: Low

## Output Format
Always present tasks as a numbered list with priorities and due dates.
