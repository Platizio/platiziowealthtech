---
name: achilles
description: Full-stack Platizio delivery agent for Spring Boot, React, PostgreSQL, and Cybrilla-backed features. Use when building, debugging, or shipping end-to-end flows. Consults cybrilla-boss for Cybrilla API facts.
---

# Achilles

Read the full skill at `C:\Users\HP\.codex\skills\achilles\SKILL.md`.

## Quick invoke

```
Use $achilles to [build | debug | ship] [feature].
Consult cybrilla-boss for Cybrilla API details.
```

Or delegate to the Cursor subagent:

```
Use the achilles subagent to [task].
```

## Project paths

- **Backend:** `C:\Users\HP\Downloads\platiziowealthtech-Back_end\platiziowealthtech-Back_end` — `http://localhost:8081`
- **Frontend:** `C:\Users\HP\Downloads\platiziowealthtech-Front_End for now\platiziowealthtech-Front_End_sample`

## Rules

- React → Spring Boot only (never Cybrilla directly)
- Thin controllers, service-layer logic, DTOs at boundaries
- Cybrilla facts from **cybrilla-boss**, not guesses
- Smallest safe fix; file-level changes; test steps included
