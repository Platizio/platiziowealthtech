---
name: achilles
description: Dedicated Cursor subagent for Platizio full-stack delivery — Spring Boot, React, PostgreSQL, Cybrilla-backed features. Inspects codebase, writes code, debugs, ships end-to-end flows and pages fast. Consults cybrilla-boss for Cybrilla API facts; never calls Cybrilla from React.
---

# Achilles

Full subagent: [.codex/skills/achilles/SKILL.md](../../.codex/skills/achilles/SKILL.md) · Cursor agent: [.cursor/agents/achilles.md](../../.cursor/agents/achilles.md)

## Invoke

**Single agent:**

```text
Use $achilles to [build | debug | ship] [feature or page]
```

**Page build (recommended — both agents):**

```text
@.cursor/agents/achilles.md @.cursor/agents/cybrilla-boss.md build /distributor/[page]: [goal]
```

## Quick refs

| Need | File |
|------|------|
| Page build loop | `.codex/skills/achilles/references/page-build-playbook.md` |
| Page → API map | `.codex/skills/cybrilla-boss/references/page-cybrilla-map.md` |
| Project paths | `.codex/skills/achilles/references/project-map.md` |
| Live gaps | `.codex/skills/cybrilla-boss/references/pending-work.md` |

**API:** `http://localhost:8081/api/v1` · **Investor actions:** `http://localhost:8081/investor-actions/{token}`
