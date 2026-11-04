# CLAUDE.md

Working notes for Claude Code in this Telegram-Android fork.

## Working agreement
- **Never stop before the work is complete.** Carry every request all the way
  through — implement, build, fix build errors, build again, then commit,
  fast-forward `main`, push to origin, and (when relevant) upload to the tablet —
  without pausing to hand back a half-finished task or to re-ask about steps
  already agreed. Stop only for a genuine blocker: information only the user can
  give, or a destructive/irreversible action outside the agreed scope.
  (Added at the user's request, 2026-11-04.)

## Build & ship (this fork)
- Build: run `build.cmd` at the repo root (~6 min). The APK lands at
  `Telegram-13.1.1.apk` in the repo root.
- After any change: commit, `git merge --ff-only` into `main` in
  `C:/my_projects/Telegram`, then `git push origin main` over SSH. Push only to
  origin (`0x7FFFFFFFFFFFFFFF/Telegram`), never to the upstream (DrKLO) repo.
- Tablet install is the side-by-side `org.telegram.messenger.beta` build,
  uploaded over FTP to the tablet's `/FTP/` directory.
- No confirmation dialogs in this fork — the user dislikes them.
