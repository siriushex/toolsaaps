# SECURITY REVIEW

## Scope
- Android app ingestion, sync, automation, outbound action delivery.
- Backend API endpoints and scheduler jobs.

## Current checklist status

### MUST
- Verify secrets are never logged (API keys, auth headers, secrets).
- Validate all inbound payload parsing paths for numeric bounds and null safety.
- Ensure outbound treatment/temp-target writes use idempotency and source tagging.

### SHOULD
- Add backend input validation tests for edge payloads and malformed JSON.
- Add rate limiting strategy for externally exposed backend endpoints.
- Add explicit PII redaction policy in logs and docs.

### NICE
- Threat model diagram for Android local transport + local Nightscout emulator.
- Security CI job (dependency audit + static checks) with baseline allowlist.

## Findings log
- No structured findings recorded yet in this document.
- Next security thread must convert checklist into concrete findings with owner and due date.
