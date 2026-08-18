# Persistence Boundary

**Introduced in Phase 1, before Room, deliberately.**

## Why the boundary comes first

Retrofitting an abstraction onto code that already imports `@Entity` and
`@Dao` means touching every call site and re-verifying every test. Defining it
first makes Phase 2 purely **additive**: write `RoomDatasetRepository`,
register it, done. Nothing in the domain changes.

```
:core domain          :core repository         :app  (Phase 2)
─────────────         ────────────────         ──────────────────────
Dataset        ─────► DatasetRepository ◄───── RoomDatasetRepository
Transform             JobRepository            RoomJobRepository
Export                ExportHistoryRepo        RoomExportHistoryRepo
                      (interfaces)             InMemoryStore (tests)
```

## Rules

1. **No Android or Room types in any signature.** Enforced by a test that
   greps `Dataset.kt` for `androidx.room`, `import android.`, `java.sql`.
2. Domain models in, domain models out — DAO entities never leak upward.
3. All methods `suspend` — implementations do I/O; callers must not block.
4. Failures are typed `RepositoryException`s, never raw `SQLiteException`.
5. **Paging is explicit.** A 10,000-row dataset must never be loaded whole to
   render 20 rows (§37, §38).

## Interfaces

| Interface | Responsibility |
|---|---|
| `DatasetRepository` | datasets + records, with paging and search |
| `JobRepository` | job definitions, runs, checkpoints, interrupted-run recovery |
| `ExportHistoryRepository` | completed exports, so one can be repeated |
| `DataKhojStore` | all three, injected as a unit; `verifyIntegrity()` |

### Paging

```kotlin
val page = store.datasets.loadRecords("big", offset = 0, limit = 100)
page.total       // 250
page.hasMore     // true
page.nextOffset  // 100
```

`listSummaries()` returns `DatasetSummary` — id, name, counts, timestamps —
and never touches records, so the datasets list stays instant regardless of
size.

### Typed failures

```kotlin
RepositoryException.NotFound(id, "dataset")
RepositoryException.AlreadyExists(id)
RepositoryException.StorageFull(cause)
RepositoryException.Corrupted(detail, cause)
RepositoryException.Failed(operation, cause)
```

The UI can map these to real messages (§34) instead of showing
`SQLiteConstraintException`.

### Process-death recovery (Phase 2 prerequisite)

```kotlin
store.jobs.saveCheckpoint(runId, """{"page":17}""")
store.jobs.findInterrupted()   // RUNNING/PAUSED with no finishedAt
```

`JobRunStatus` includes `PARTIALLY_COMPLETED` because 80 of 100 pages
succeeding is a usable result, not a failure (§33).

## InMemoryStore

`InMemoryStore` is the reference implementation. It:

1. proves the interfaces are implementable
2. gives every test a fast fake — no Room, no emulator
3. **defines the semantics `RoomDatasetRepository` must match** in Phase 2

It holds everything in memory and does not survive process death. That is
exactly what Phase 2 fixes.

## Phase 2 checklist

- [ ] `RoomDatasetRepository`, `RoomJobRepository`, `RoomExportHistoryRepository`
- [ ] Run the same contract tests against Room and InMemory — identical results
- [ ] Migrations that never delete user data
- [ ] `verifyIntegrity()` backed by `PRAGMA integrity_check`
