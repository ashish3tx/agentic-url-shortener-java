# Architecture

## Two planes

```
                     ┌──────────────────────────────────────────────────┐
  requirement ──►    │                SDLC control plane                │
  (scenario)         │  OrchestratorEngine (DAG scheduler, virtual thr.)│
                     │  Gates · PolicyEngine · Agents · Telemetry       │
  humans ──approve──►│  Approvals · Decision lineage · Re-plan          │
                     └───────────────┬──────────────────────────────────┘
                                     │ reasons over / verifies / tests
                                     ▼
                     ┌──────────────────────────────────────────────────┐
  client ──HTTP──►   │                  product plane                   │
                     │ RateLimitInterceptor → Controllers → Shortener-  │
                     │ Service → LinkValidator / UrlRepository (port)   │
                     │ HousekeepingJobs (retention, limiter eviction)   │
                     └──────────────────────────────────────────────────┘
```

The control plane is the main thing the assignment is judged on. The shortener is the system the agents reason about, verify and test.

## Package layout

```
com.agentic.shortener
├── config/            ShortenerProperties, OrchestratorProperties (records), Clock, OpenAPI
├── common/            ClientIdentity, Hashing, RequestIdFilter, GlobalExceptionHandler (RFC 7807)
├── security/          SecurityConfig (JWT / Basic), Roles, AppSecurityProperties, Callers
├── url/
│   ├── domain/        ShortUrl, ClickEvent, AnalyticsSummary, CreateLinkCommand (records)
│   ├── error/         sealed ShortenerException hierarchy
│   ├── repository/    UrlRepository + IdempotencyStore ports, in-memory adapters (sandbox/tests)
│   │   └── jpa/       JPA entities + adapters (the production beans)
│   ├── service/       ShortenerService, LinkValidator (SSRF), CodeGenerator, VisitorHasher, HousekeepingJobs
│   ├── ratelimit/     SlidingWindowRateLimiter
│   └── web/           ShortUrlController, RedirectController, WriteRateLimitInterceptor, DTOs
└── orchestrator/
    ├── model/         StageSpec (+builder), RunContext, StageContext, enums, Approval, DecisionRecord
    ├── graph/         StageGraph (validated DAG), ScenarioGraphs, ScenarioCatalog (startup validation)
    ├── gate/          Gate enum (entry/exit predicates)
    ├── agent/         Agent SPI + Requirements, NormalizeScope, Architecture, ImpactAnalysis,
    │                  Implementation probes, IntegrationSync, ContractTestSuite, Testing, Documentation, Release
    ├── policy/        PolicyEngine (security, compliance, change control)
    ├── telemetry/     OrchestratorTelemetry (audit trace + Micrometer), ReliabilityMetrics
    ├── engine/        OrchestratorEngine, Run, StageRuntime, views
    └── web/           OrchestratorController
```

## Product design

| Concern | Decision |
| --- | --- |
| Codes | 7-char `SecureRandom` base62 (≈3.5e12 space), so codes are unguessable and can't be enumerated. Collisions are retried via atomic `saveIfAbsent` |
| Alias uniqueness | primary-key insert (`Persistable.isNew` forces INSERT, a duplicate fails instead of merging). Race-free, tested with concurrent claims on the real DB |
| Updates | immutable `ShortUrl` record + `SELECT … FOR UPDATE`, giving atomic read-modify-write |
| Idempotency | key scoped per caller. **Reservation**: insert the key row first (PK), create the link, then complete the row; failures release it. Concurrent duplicates get 409 "in progress". A payload-hash mismatch returns 422 |
| SSRF / open redirect | http(s) only. No userinfo. Blocks loopback, RFC1918, link-local (cloud metadata), CGNAT, ULA, reserved, IPv4-mapped IPv6, and decimal/hex/octal IP encodings. No DNS on the write path |
| Privacy | click IPs stored as `sha256(salt:day:ip)` prefix; the salt is a required secret in prod |
| Redirect | 302 + `no-store`. A 301 would be cached by browsers, which bypasses analytics and outlives deactivation |
| Rate limit | sliding-window log per authenticated user, mutated inside `ConcurrentHashMap.compute` (no extra locks). Applied to writes only, so the redirect hot path stays cheap. Per node |
| Retention | scheduled purge of clicks older than `shortener.analytics.retention` (30d) and idempotency keys older than 24h |
| Errors | sealed exception hierarchy plus pattern-matching `switch` gives an exhaustive status mapping. The compiler flags a new exception type that has no HTTP mapping |
| Threads | `spring.threads.virtual.enabled=true`; Tomcat requests run on virtual threads |

## Orchestration model

A run is a **stateful DAG**, not a linear chain. Every `StageSpec` declares:

- `dependsOn` + `join` (`ALL` / `ANY`) for synchronisation, and `parallelGroup` for documentation
- `entryGates` / `exitGates`: `Gate` enum predicates over the shared `RunContext`
- `autonomy`: `FULL` · `SUPERVISED` · `HUMAN_GATED`
- `approvalKind`: `ASSUMPTION_ACCEPTANCE` · `PUBLIC_API` · `PRODUCTION_DEPLOY`
- `maxAttempts` + exponential `backoff`, `fallbackAgent`, `rollbackAgent`

```
GREENFIELD / BROWNFIELD / AMBIGUOUS share this spine:

requirements ─► [impact]* ─► [normalize: HUMAN]** ─► architecture
                                                         │
                       ┌─────────────┬───────────────────┤      (parallel, virtual threads)
                    impl_A        impl_B              impl_C
                       └─────────────┴─────────┬─────────┘
                                         sync_impl (join=ALL, exit: IMPLEMENTATION_COMPLETE)
                                    ┌──────────┴──────────┐
                                 testing             documentation   (parallel)
                       (retry ×3 → fallback smoke)       │
                                    └──────────┬──────────┘
                                            release  (entry: TESTS_GREEN, DOCS_PRESENT, NO_POLICY_VIOLATIONS
                                                      exit: RELEASE_READY, HUMAN approval, rollback agent)
 * brownfield only   ** ambiguous only
```

### Control flow for one stage

1. **Policy (stage start):** release must be human-gated, and a human-gated stage must declare an approval kind.
2. **Entry gates:** refuse to start on incomplete upstream context.
3. **Agent:** reads `StageContext` and publishes artifacts. Every publish goes through one choke point: **secret scan → sha256 → provenance (producer, version) → audit event**.
4. **Exit gates:** refuse to mark success unless the predicates hold, e.g. `TESTS_GREEN` reads the real test report.
5. **Human checkpoint:** if `approvalKind` is set, the stage parks in `WAITING_APPROVAL` and the run in `WAITING_APPROVAL`. Approval warnings are pulled from the stage's artifacts, so a degraded test run is shown to the approver.
6. **Failure handling:**
   - Agent errors and gate failures go to **bounded retry** with exponential backoff.
   - When retries are exhausted, the **fallback agent** runs, through the *same* gates.
   - If that also fails, the stage fails, the **rollback agent** runs, and the run fails fast: no new stages start, in-flight siblings drain.
   - **Policy violations are never retried.** Repeating a non-compliant action won't make it compliant.
7. **MTTR sample:** when a stage recovers, the time from its first failure to recovery is recorded.

### Scheduler (concurrency model)

- One **virtual thread per run** runs `drive()`. It holds the run's `ReentrantLock` and waits on a `Condition`. Every transition (stage done, approval, stop, amend) calls `signalAll()`, so the loop reacts immediately. A 250 ms tick is only a safety net.
- **Stage workers** are virtual threads. They hold the lock only for state transitions, never while an agent runs.
- `ReentrantLock` rather than `synchronized`: in JDK 21 a virtual thread that blocks inside `synchronized` pins its carrier thread.
- `RunContext` uses concurrent collections, and artifacts are stored as unmodifiable snapshots, so parallel agents never share mutable state. Each implementation stream writes its own `impl.<part>` artifact, and `sync_impl` merges them.

### Human-in-the-loop controls

| Control | Behaviour |
| --- | --- |
| Approve | Checks separation of duties: initiator ≠ approver for `PUBLIC_API` / `PRODUCTION_DEPLOY`. The `auto-approve` actor is reserved for runs started with `autoApprove=true` (allowed only when `orchestrator.governance.auto-approve-allowed`) |
| Reject | Fails the stage, runs the rollback agent (`release` → `NO_GO`), fails the run |
| Safe-stop | Drains in-flight stages. Pending and waiting stages are `SKIPPED` and approvals `SUPERSEDED`. Run ends `STOPPED` |
| Amend → re-plan | A human changes an artifact. The engine finds its producer and computes all **descendants** in the graph. Any that already ran are reset to `PENDING`, their artifacts removed and their approvals superseded, and they re-execute against the new context. If the producer itself was human-approved, a fresh approval is requested. Amending while affected stages are in flight → 409 |

### Cross-stage context and lineage

- `artifacts`: requirements, normalized_scope, impact, architecture, impl.*, implementation, tests, documentation, release
- `artifactLineage`: `{producedBy, sha256, version, producedAt}` per artifact. `version > 1` means it was amended or re-produced
- `decisions[]`: AUTONOMY, GATE, RETRY, FALLBACK, ROLLBACK, APPROVAL, POLICY, REPLAN, SAFE_STOP, each with rationale, outcome and actor
- `trace`: append-only, sequence-numbered events, mirrored to the `audit.orchestrator` logger

### Reliability metrics

`GET /orchestrator/metrics`, also exposed as Micrometer meters (`orchestrator.*`):

| Metric | Definition |
| --- | --- |
| success rate | succeeded / finished runs |
| retries, fallbacks, rollbacks, replans, approvals requested | counters |
| MTTR | mean time from a stage's first failed attempt to its recovery (retry or fallback) |
| e2e latency | mean run wall-clock time. This includes human wait time, which is reported honestly rather than hidden |

## Production topology

```
           ┌──── OIDC IdP (JWT, roles claim)
           │
client ─► LB/ingress ─► app (N replicas, prod profile) ─► PostgreSQL (Flyway-managed)
           (TLS)          │  /actuator/health/{liveness,readiness}  ◄── k8s probes
                          │  /actuator/prometheus                   ◄── Prometheus (role OPS)
                          └─ JSON logs (ECS, requestId)             ──► log pipeline
```

| Concern | Where it lives |
| --- | --- |
| Authentication | `security/SecurityConfig`: JWT resource server (prod), HTTP Basic (local); stateless; fails startup if neither is configured |
| Authorization | URL rules by role + link ownership in `ShortenerService` (`Caller.canManage`) + four-eyes in `PolicyEngine` |
| Persistence | `url/repository/jpa/*` and `orchestrator/telemetry/jpa/*` behind ports; schema in `db/migration/V1__initial_schema.sql`; Hibernate `validate` |
| Idempotency | reservation row (PK) → create → complete; released on failure; 409 while in flight |
| Correlation | `RequestIdFilter` puts `X-Request-Id` into MDC and the response |
| Config safety | `@Validated` properties; prod placeholders have no defaults |
| Shutdown | `server.shutdown=graceful`; engine `@PreDestroy` safe-stops active runs, then interrupts after `orchestrator.shutdown-grace-period` |

## Key decisions

1. **Spring Boot 4.1 (Spring Framework 7, Jackson 3) + JDK 21, PostgreSQL in prod and H2 locally.** One Flyway migration serves both, so `./mvnw spring-boot:run` needs no infrastructure while prod runs on a real database. Persistence sits behind ports (`UrlRepository`, `IdempotencyStore`, `AuditTrail`).
2. **Agents verify and test the real code; they don't generate it.** Each implementation agent runs behavioural probes on its slice (e.g. 9 SSRF vectors, limiter semantics). The testing agent runs a contract suite in a sandbox built from the real `ShortenerService`. That keeps the pipeline honest, since nothing can print "passed" without running anything. In a real deployment, LLM-backed agents plug in behind the same `Agent` SPI and stay inside the same gates and policy.
3. **Graphs are validated at startup.** `ScenarioCatalog` rejects cycles, unknown dependencies, unknown agents, and releases that aren't human-gated. A mis-wired graph fails the deploy, not a run.
4. **Policy is a single, non-bypassable choke point.** Artifact writes, stage starts, run starts and approvals all go through `PolicyEngine`.
5. **The fallback reports reduced coverage instead of hiding it.** Its report is marked `degradedCoverage`, and that warning lands on the approval, so a human decides whether reduced coverage is acceptable.
6. **Run state in memory, audit in the database.** Execution state (locks, conditions, in-flight futures) stays in the JVM for simplicity and speed. Everything needed for audit and lineage is persisted. Finished runs are evicted after `orchestrator.run-retention`, and their trace stays queryable.
7. **In-memory adapters are kept, but not as beans.** The testing agent runs its contract suite against the real `ShortenerService` wired to in-memory adapters, so in-run validation never writes to the production database.
