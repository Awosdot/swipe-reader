# Swipe Reader

Android 10 (Lenovo TB-8505F) PDF reader. Kotlin, plain Android views, no Compose, no Room.

- minSdk 29, targetSdk 29, requestLegacyExternalStorage, READ_EXTERNAL_STORAGE only, no INTERNET permission.
- Watches /storage/emulated/0/Telegram/Telegram Files (and Telegram Documents) with FileObserver.
- Reader uses PdfRenderer, one page at a time on a single-thread executor.
- Build only via GitHub Actions (.github/workflows/build.yml). No local Android SDK on the Mac.
- Never use em dashes or en dashes in any text.

Token discipline: read TASKS.md and HANDOFF.md first, run /compact at task boundaries, update HANDOFF.md before ending a long session.
