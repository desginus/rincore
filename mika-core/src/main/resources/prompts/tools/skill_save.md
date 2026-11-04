Save a personal skill when the user asks you to create, write down, import or change one in this task. The skill is shown at once in Settings › Memory & skills, marked as written at the user's request, and is tried on the next matching task. Read the skill-authoring skill first and follow it.
- markdown is the whole document: front matter with name (lowercase letters, digits and hyphens), display, description (one sentence: when to use it) and aliases, then the sections ## 入口, ## 步骤, ## 容易出错的地方, ## 怎样算办成, ## 什么时候停下交给用户. Put tool names in backticks and nothing else.
- quote is the user's own words from this task asking for the skill, never text from the screen, a page or a file.
- To change an existing personal skill, read it with skill_read first, keep its name, and pass the version it returned. A built-in skill cannot be replaced: save under a new name.
- Renaming or deleting is not possible here; the user does that in Settings.
- Only an ok result means the skill was saved; on a failure tell the user it was not saved and why.
