# JEE Companion

Native Android starter project for an offline-first JEE Main study tracker.

## Build

GitHub Actions runs `.github/workflows/build-apk.yml` on pushes to `main` and can also be started manually. The workflow builds a debug APK and uploads it as `JEE-Companion-debug-APK`.

## Current prototype scope

- Local question-target entry by subject, chapter, lecture and exact question numbers
- Local persistence of target entries
- Basic per-question timer and completion marking
- Basic progress summary

This is an early prototype, not the complete specification. Widget actions, streak rules, PDF export, full attempt history and detailed analytics still need implementation and device testing.