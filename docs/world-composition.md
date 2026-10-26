# World composition (#66)

Build immutable successful story-version references and adjacent successful bridges in date order. Folder selection and completed composition are separate. Each world retains one completed result; a failed replacement does not destroy it. No text/content snapshot is duplicated in composition tables and no credits are charged.

## API

All requests use an onboarded Bearer token and no-store responses.

- POST /api/v1/worlds/{worldId}/composition: folderRevision + compositionRevision (0 before the first result). Returns202 after durable acceptance. At least two selected dreams with current successful stories are required.
- GET /api/v1/worlds/{worldId}/composition: folderRevision, compositionRevision, hasResult, status, failureCode, attemptId, jobVersion, selectionChanged. attemptId=null means no job yet. GET never generates text.
- GET /api/v1/worlds/{worldId}/composition/entries: size1..50/default20 + opaque cursor. Contains only the published version, fixed story sections, bridge before each story, sourceChanged/newStoryAvailable/connectionGap. Use nextCursor verbatim; changed composition revision returns409.
- POST /api/v1/worlds/{worldId}/composition/retry: latest jobVersion. Reuses frozen inputs and successful bridges. Failed/expired bridges are retried only on this explicit action. Changed source/folder/composition/settings require a new build.

PENDING/PROCESSING is asynchronous; poll status. FAILED may still have hasResult=true. Identical active requests reuse a job. An old request after publication gets409 and must reload. No completed-history API is provided.

## Execution

Durable candidates protect all related dreams from editing, deletion and narrative regeneration, including between bridge calls. The worker processes bounded bridge steps and releases all database transactions before external IO. Existing same-user bridge input/config keys reuse their successful result. Pages are bounded independently of selection count.

MONGLE_WORLD_COMPOSITION_ENABLED gates new build/retry requests. MONGLE_WORLD_COMPOSITION_WORKER_ENABLED controls each instance's worker. At least one worker instance must run. Poll2s, batch20, maxConcurrentCalls2; allowed intervals100ms..1min, batch1..100, calls1..8. Calls also share the existing bounded AI resources. Disabling new requests does not discard accepted work.

Each step has a120s claim and180s renewable job lease. Lost claims fail with WORKER_INTERRUPTED; expired jobs fail with ATTEMPT_EXPIRED. They release mutation protection without waiting for a client request. Explicit retry reconciles bridge state instead of replaying unknown requests automatically. Folder/config/revision drift prevents publication. Only after all elements succeed are old result references replaced atomically.

## Database and verification

New DB: schema-initial.sql. Existing DB: stop app/workers, back up, then apply db/migrations/20261026-world-composition.sql. Hibernate validates two new tables. Actual MySQL/RDS execution and external AI quality require live integration verification.

## Explicit partial apply

POST /api/v1/worlds/{worldId}/composition/dreams/{dreamId}/apply accepts folderRevision, compositionRevision and the new storyVersionId. The target must exist in the actual completed composition; current folder selection is independent. A different version must be the dream's current successful version. Copies the completed references into candidates, replaces only the target and rebuilds only its preceding/following bridges. Other dreams retain their fixed versions, including their preexisting gaps. The same fixed version is a200 no-op; accepted updates are202. Failure keeps every prior story/bridge reference; retry reuses already completed candidate bridges.
