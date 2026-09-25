# CLAUDE.md — session memory (dedicated branch only)

This file lives only on the `claude-memory` branch. It is deliberately kept
off `master`, `mybase`, and every feature branch — it's not merged into any
of them, so it never shows up in a PR diff or in project code branches. It
exists to give a future Claude Code session working in this repo the context
on past decisions recorded below. To read it in another checkout, fetch and
check out (or `git show`) the `claude-memory` branch specifically.

## Repository relationship

- `origin` = `alexandrelimassantana/nurgling2` (this fork).
- The real upstream is `aleksandrsvoboda/nurgling2` (default branch `master`),
  confirmed via the GitHub "forked from" label and `git merge-base` (origin/master
  was a clean ancestor of upstream/master). A local `upstream` remote was added
  for this: `git remote add upstream https://github.com/aleksandrsvoboda/nurgling2.git`.
- `origin/master` was 39 commits behind `upstream/master` when discovered
  (2026-09-25). Brought current by fast-forward pushing `upstream/master` onto
  `origin/master` directly (`git push origin upstream/master:refs/heads/master`),
  since origin/master had no local commits of its own — it's a pure mirror of
  upstream's master, never developed on directly.
- Katodiy/nurgling2 was checked and ruled out as the parent (unrelated git
  history, no common ancestor) — don't assume it's upstream again.

## Branch organization decisions

The original `autocraft-output-categories` branch mixed two unrelated feature
sets across 7 commits (with a duplicate/superseded pair of crucible fixes from
a parallel push). It was split as follows:

- **`autocraft-output-categories`** — output categories + craft-quantity
  calculator. Autocraft preset metal/ingredient selection, `Craft.java`
  category-name-vs-chosen-ingredient fix, `MaintainStockBot` output-multiplier
  fix. Touches: `AutocraftBot.java`, `Craft.java`, `MaintainStockBot.java`,
  `CraftPreset.java`, `SaveCraftPresetDialog.java`, `StepSettingsPanel.java`,
  `CraftPresetsPanel.java`.
- **`feat/compenent-crafter/light-action`** (renamed from `light-action`) —
  crucible auto-ignition accepting branch fuel, not just coal. Touches only
  `PrepareWorkStation.java`.
  - Note: the original branch had two independent implementations of this fix
    (`b71cd16`+`8968c05` and `9d48ee0`) that got merged together, with the merge
    silently keeping `9d48ee0`'s version wholesale and discarding the other
    (verified byte-for-byte). Only `9d48ee0`'s content was carried into this
    branch — the superseded duplicate commits were intentionally dropped.
  - The old `light-action` branch name could not be deleted from `origin`
    (push --delete returned HTTP 403 — looked like a token-scope restriction,
    not a real permission error, since normal pushes work). It may still exist
    as a stale remote branch; delete manually if desired.
- **`mybase`** — tracks upstream `master`, with both feature branches merged in
  via `--no-ff` merges (not rebased flat). Rebuild this branch (reset to
  `origin/master`, re-merge both feature branches) rather than trying to
  fast-forward it whenever the feature branches change.
- A "chicken configurations" change was asked about but does not exist
  anywhere in this repo's history (checked all commits on this branch and
  every other remote branch at the time). User confirmed: skip it. Don't
  assume it exists in future sessions without re-checking.

## Sync procedure used (for repeating later)

1. `git fetch upstream master`
2. Diff upstream's new commits against files our branches touch — if none
   overlap, rebases/merges are conflict-free.
3. `git push origin upstream/master:refs/heads/master` (fast-forward origin's
   mirror of master).
4. `git fetch origin master`, then rebase each feature branch:
   `git checkout <branch> && git rebase origin/master`.
5. Rebuild `mybase`: `git checkout -B mybase origin/master`, then
   `git merge --no-ff` each feature branch in turn.
6. Push all three with `--force-with-lease` (they're rewritten histories).

No PRs were open against any of these branches at the time of these rewrites,
so force-pushing was safe. Re-check for open PRs before force-pushing again.

## This branch (`claude-memory`)

Created from `origin/master`, holding only this file. Never merge it into
`master`, `mybase`, or a feature branch — keep it as a standalone reference
branch. To update this memory later: `git checkout claude-memory`, edit
`CLAUDE.md`, commit, and `git push`.
