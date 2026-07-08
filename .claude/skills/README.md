# Project Claude Skills — UI/UX Pro Max

These are project-scoped [Claude Code skills](https://code.claude.com/docs)
committed to the repository. Because they live under `.claude/skills/`, Claude
Code auto-discovers them for **anyone who checks out this repo** — on any
machine, in any Claude Code session — with no per-machine setup required. This
is how the skill is shared across different PCs and users who have access to
this repository.

## Installed skills

| Skill | Purpose |
|-------|---------|
| `ui-ux-pro-max` | Core UI/UX design intelligence: styles, palettes, font pairings, charts, stacks |
| `design` | Brand identity, design tokens, logos, banners, icons, social photos |
| `design-system` | Token architecture and component specifications |
| `ui-styling` | shadcn/ui + Tailwind component styling |
| `brand` | Brand voice and visual identity |
| `banner-design` | Social/ad/web/print banner design |
| `slides` | Strategic HTML presentations |

Source: [`ui-ux-pro-max-cli`](https://www.npmjs.com/package/ui-ux-pro-max-cli)
(the `uipro` command).

## How other machines get it

Just clone/pull this repo. The skills are already here — nothing else to install.

## Updating

To pull a newer version of the skill assets:

```bash
npm install -g ui-ux-pro-max-cli   # get the latest CLI
uipro update --ai claude           # refresh skill files in this project
# or: uipro init --ai claude --force
```

Then commit the changes so every other checkout gets the update.

## Optional: also install globally (per-machine)

A global install makes the skill available in **every** project on that one
machine (not shared via git):

```bash
uipro init --ai claude --global
```
