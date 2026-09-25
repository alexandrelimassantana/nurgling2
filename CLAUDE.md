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
- **`mybase`** — tracks upstream `master`, plus both feature branches' commits.
  Originally built with `--no-ff` merges, but rebuilt on 2026-09-25 as a fully
  linear history with **no merge commits of our own** (the user asked for
  merge commits to be eliminated so history reads as a flat sequence of the
  additions from each branch). Rebuild recipe: both feature branches are kept
  rebased directly onto `origin/master` (linear, disjoint files, no overlap),
  so `mybase` is just `git checkout -B mybase origin/autocraft-output-categories`
  followed by `git cherry-pick origin/master..origin/feat/compenent-crafter/light-action`.
  Verify with `git diff <old-mybase> <new-mybase> --stat` (should be empty)
  before force-pushing. Do NOT use `git merge --no-ff` for this branch anymore
  — keep it linear going forward, including after future upstream syncs.
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
5. Rebuild `mybase` as a linear history (no merge commits — see "Branch
   organization decisions" above): `git checkout -B mybase origin/<first-feature-branch>`,
   then `git cherry-pick origin/master..origin/<each-other-feature-branch>` in
   turn. Only works cleanly while the feature branches touch disjoint files;
   if they start overlapping, cherry-picks may need conflict resolution.
6. Push all three with `--force-with-lease` (they're rewritten histories).

No PRs were open against any of these branches at the time of these rewrites,
so force-pushing was safe. Re-check for open PRs before force-pushing again.

## This branch (`claude-memory`)

Created from `origin/master`, holding only this file. Never merge it into
`master`, `mybase`, or a feature branch — keep it as a standalone reference
branch. To update this memory later: `git checkout claude-memory`, edit
`CLAUDE.md`, commit, and `git push`.
