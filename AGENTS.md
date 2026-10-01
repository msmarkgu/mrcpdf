# Working conventions

## Save plans to `./notes/`, scratch to `./tmp/`

**Plans go in `./notes/`.** Write the plan to a file *before* starting work,
not after. Session context is lost on a crash or a compaction, and a plan that
only ever existed in the conversation is gone exactly when it is most needed —
which is right after an interruption, when the temptation is to start
something new. `notes/plan-YYYY-MM-DD.md` is the convention; the file is
untracked because `notes/` is gitignored, which is intentional: design notes
are local working material, not part of the build.

On resuming work after an interruption, read the newest `notes/plan-*.md`
first, then check `git log` and `git status` to see how far it actually got.
A plan file describes intent, not state.

**Intermediate and scratch files go in `./tmp/`.** Not `/tmp`. The project
directory survives a crash or a container restart; `/tmp` frequently does not,
and anything worth recovering from will already be gone by the time you go
looking for it. `./tmp/` is gitignored (`.gitignore:30`).

Two directories that look like scratch but are not:

- `build/` — gradle's declared output directory. Declared task inputs and
  outputs live here, so moving or clearing it breaks up-to-date checks and can
  force a multi-hour regeneration. Treat it as expensive, not disposable.
- `temp/` — per-run page images from the pipeline itself, gitignored
  (`.gitignore:29`).

## Before committing

Run `./gradlew test`. 182 tests cover the scorers, the metric calibration
anchors and the pipeline regressions; they are the only guard that a retune
has not quietly broken an invariant.

A test that cannot fail is worse than no test, because it is read as coverage.
Every test added for a retune should be checked once against the old
behaviour -- reintroduce the bug, watch the test go red, revert. Two tests
written to "prove" an RNG defect here passed against the unfixed code, and the
defect they were built around was an artifact of the analysis script.
