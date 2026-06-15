# OpenAssist3 Production Readiness Audit

## Executive summary

OpenAssist3 is now treated as a real Android product, not a UI-only demo. The current implementation contains working foundations for secure settings, OpenRouter chat, Android device tools, DownloadManager-backed model downloads, and local-runtime failure handling, but several advanced systems are intentionally disabled unless their real runtime/provider is present.

## Build stability

- Required Java version: 17.
- CI must run source policy, unit tests, Android lint, debug build, and release build.
- Binary archives are not allowed in source PRs after extraction.

## Architecture

- UI state is owned by ViewModels.
- Network calls belong in repositories.
- Secure settings are stored through `SecureStorage`.
- Local AI calls must go through `LlamaCppBridge` and fail safely when a real llama.cpp runtime is not installed.
- Android device actions must require explicit user confirmation when sensitive.

## Missing production features

These features must remain disabled or fail safely until implemented with real providers/runtimes:

1. Native llama.cpp runtime binding for token streaming and cancellation.
2. WorkManager download orchestration with pause/resume and checksum verification.
3. Full MCP runtime management for HTTP, SSE, and local servers.
4. Persistent Room conversation database with search, archive, pin, import, and export.
5. Sandboxed Python execution runtime.
6. Persistent background agent queue with notifications.
7. Ticket submission backend; email fallback may remain.

## Security findings

- API keys must never be stored in plain text.
- Model downloads must require HTTPS.
- Device tools must validate inputs and require permissions.
- Logs and diagnostics must redact secrets before export.
- Unsupported runtimes must fail closed with user-facing explanations.

## Performance findings

- OpenRouter calls need explicit connect/read/write/call timeouts.
- Retry policy must be bounded and cancellation-friendly.
- Local inference must run off the main thread.
- Storage scanning must be incremental for large file trees.

## Implementation plan

1. Keep CI authoritative for every PR.
2. Stabilize Gradle and remove tracked binary archives.
3. Harden OpenRouter networking with timeouts, retries, cancellation-aware coroutine use, and HTTP error mapping.
4. Convert UI-only advanced features into real implementations or production-safe disabled states.
5. Add Room, WorkManager, llama.cpp JNI, MCP runtime, and Python sandbox in separate focused PRs with tests.
