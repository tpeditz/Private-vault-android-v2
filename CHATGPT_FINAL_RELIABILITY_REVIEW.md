# V2.0.4 final independent reliability candidate

Baseline: owner-provided final reliability repair source ZIP, SHA-256
4dac9bbbff71d076f0db9bfee0017cbbcb6c581d28074c7085d8bf83a9d9b562.
The owner's report recorded 126 passing JVM tests, zero lint errors,
and 74 warnings; device instrumentation was compiled but not executed.

Focused follow-up changes, authorized by the owner:
- Local backup completion propagates flush and required regular-file sync
  failures. Pipe-backed SAF streams use successful flush/close, without
  claiming a provider-specific durability guarantee.
- Restore results are checked for cancellation at completion. If rollback
  removed temporary staging, the stale preview is cleared and the UI asks
  the user to select their original backup again. No empty staging fallback.
- Result/progress delivery is scoped to the current backup/restore/upload
  operation. Input buffers and streams are cleaned independently of the
  UI result callback.
- Four focused unit checks for ordinary output finalization and operation
  result ownership. Expected total: 130 declarations; actual CI pending.

Existing PVAULTB3 and legacy readers, app identity, prior fixes, signing
and OTA workflows are preserved. Device recovery, live Drive, SAF and
Notes lifecycle verification remain required. This is a test candidate
only, not a public release.
