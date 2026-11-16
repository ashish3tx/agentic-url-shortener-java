# Engineering summary

## Plan and rationale

The assignment's main differentiator is **governed, non-linear orchestration of the SDLC**. A "prompt → code" chain would miss it. The plan:

1. **Normalize the brief** into two planes: a product (the shortener) and a control plane (the orchestrator) that turns a requirement into reviewable output.
2. **Build a small but production-shaped product.** The aim was correct concurrency, real security controls and a clean API contract, so agents have something real to reason about and test.
3. **Build the orchestrator as an explicit DAG engine** with declarative stage specs. Gates, policy, approvals, retry/fallback/rollback, safe-stop and re-plan are engine features, not agent conventions.
4. **Encode three scenarios as different graphs.** Brownfield adds impact analysis; ambiguous adds a human interpretation checkpoint.
5. **Put validation in the execution path.** Gates read real reports, the testing agent runs real code, and integration tests cover every control.

Order of work: domain + validator → service + concurrency tests → web/ProblemDetail → orchestrator model/graph/gates → agents → engine → API → integration tests → dashboard/docs. Each layer compiled and was tested before the next was built.

## Artifacts

| Artifact | Location |
| --- | --- |
| Runnable service + dashboard | `./mvnw spring-boot:run` → http://localhost:8080 |
| Product code | `src/main/java/com/agentic/shortener/url` |
| Orchestrator | `src/main/java/com/agentic/shortener/orchestrator` |
| API contract | `/v3/api-docs` (live), `docs/openapi.json` (snapshot), `/swagger-ui.html` |
| Tests (76) | `src/test/java/...` — `./mvnw verify` |
| Demo | `scripts/demo.sh`, `requests.http` |
| Design docs | `ARCHITECTURE.md`, `docs/scenarios.md` |
| Per-run outputs | `GET /orchestrator/runs/{id}`: normalized requirements, ADRs, impact map, implementation evidence, test report, documentation index, release go/no-go, lineage, approvals, decisions, metrics |

## Validation strategy

- **Unit:** validator (33 parameterised SSRF/scheme cases), rate limiter (sliding window, eviction, 500-thread concurrency), service (alias race, idempotency scope/mismatch, TTL with a movable clock, retention), graph (cycles, duplicates, topology).
- **Integration (`@SpringBootTest` + MockMvc):** full HTTP contract, problem details, 429, OpenAPI exposure, and every orchestration control (see README table).
- **In-run:** the testing agent's contract suite is the release exit gate. `failureMode` injection shows retry and fallback without faking success.
- **Determinism:** tests wait on the engine's condition variable (`awaitSettled`), not sleeps. The orchestrator suite passed 5 consecutive runs.

## Risks, trade-offs and failure scenarios

| Risk / failure | Mitigation | Residual |
| --- | --- | --- |
| Open redirect / SSRF via destination | http(s) only; userinfo, loopback, private, link-local, CGNAT, ULA, reserved and numeric-encoded IPs blocked | **DNS rebinding:** hostnames aren't resolved at write time. A public name that later resolves privately isn't caught. Redirects are browser-side, so server-side SSRF exposure is low. A link-preview/scan feature would need resolve-and-pin |
| Link enumeration | `SecureRandom` 7-char base62 | Custom aliases are guessable by design |
| Unauthorised access / impersonation | Spring Security: OIDC JWT (prod) or Basic (local), role-based URL rules, link ownership, actor taken from the principal (never from the request body), four-eyes | Roles come from the IdP's token claim, so their correctness is the IdP's responsibility |
| Write abuse | per-user sliding window, reservation-based idempotency keys in the database | Rate-limit windows are in-memory per node; a cluster gives each node its own window (Redis/Bucket4j next) |
| Concurrency bugs | DB primary keys for alias/idempotency races, `SELECT … FOR UPDATE` on updates, immutable records, single lock per run, `ReentrantLock` (no virtual-thread pinning) | Verified on H2 (PostgreSQL mode); not yet run against a real PostgreSQL (no Docker on the build machine) |
| Agent runaway / hung agent | bounded attempts + backoff, **per-stage timeout with cancellation**, fallback, fail-fast, safe-stop | An agent that ignores interruption keeps its virtual thread busy until it returns (the stage still fails on time) |
| Unreviewed high-impact change | release must be HUMAN_GATED (checked at startup and at run time); four-eyes; reserved auto-approver | `auto-approve-allowed` must be `false` in real environments |
| Stale work after scope change | re-plan invalidates descendants, removes their artifacts, supersedes approvals, re-requests approval of an amended human-approved output | Amend is refused (409) while affected stages are in flight. The caller retries |
| Secret leakage into artifacts | regex scan on every publish and amendment, never retried | Heuristic, not a full secret scanner |
| Mis-wired graph | startup validation (cycles, unknown deps/agents, release policy) | — |
| Data loss | links, clicks, idempotency keys and the orchestrator audit trail in PostgreSQL; Flyway-managed schema; Hibernate `validate` only | In-flight run *state* is in memory. A restart safe-stops active runs (graceful shutdown) but doesn't resume them |
| Misconfiguration | `@Validated` properties, prod placeholders without defaults, startup fails without an auth mechanism | — |
| Audit gaps | every event and decision persisted; audit writes fail open with an alertable counter (`orchestrator.audit.write.failures`) | Fail-open is a deliberate availability trade-off; a strict regime would fail the stage instead |

**Trade-offs made deliberately**

- *Verification agents over code-generating agents:* deterministic, testable and honest, at the cost of not demonstrating LLM codegen. The `Agent` SPI is the seam where LLM-backed agents plug in under the same gates.
- *One lock per run:* simple and provably consistent. Throughput per run is irrelevant because stages take milliseconds to minutes.
- *Re-plan invalidates whole descendant subtrees:* sometimes more work is redone than strictly needed, but it is always correct. Diffing outputs to skip unchanged stages is an optimisation for later.
- *E2E latency includes human wait:* this is what the business experiences. Agent-only time could be added as a separate metric.

## Assumptions

- JDK 21 is available. Local evaluation needs no infrastructure (embedded H2). Production needs PostgreSQL and an OIDC issuer.
- An OIDC identity provider issues JWTs with a `roles` claim (configurable) containing USER / ENGINEER / APPROVER / ADMIN / OPS.
- For the ambiguous scenario: API-key/IP identity, 30-day retention, default-deny destinations, four-eyes on deploy.
- `autoApprove` exists for demos and tests and appears in the lineage as an explicit actor.

## Production hardening (done)

| Area | What was added |
| --- | --- |
| Persistence | Spring Data JPA adapters behind the existing ports. Flyway migration `V1`, portable PostgreSQL ⇄ H2. DB-side analytics aggregation. Retention purge via bulk `DELETE` |
| Correctness under concurrency | Alias uniqueness by primary key. Idempotency by **reservation** (reserve → create → complete, released on failure, 409 while in flight). Pessimistic lock on updates |
| Security | OIDC resource server (JWT roles claim) + Basic for local. Roles USER/ENGINEER/APPROVER/ADMIN/OPS. Link ownership. Actor from principal. Stateless, no CSRF surface, no browser auth prompts. No generated default user |
| Orchestrator | Per-stage timeout with cancellation, durable audit trail (events + decisions), bounded in-memory runs (TTL + cap), graceful shutdown that safe-stops runs |
| Operations | `prod` profile with required env secrets and fail-fast validation. JSON (ECS) logs with `X-Request-Id` correlation. Prometheus endpoint (role OPS). Public liveness/readiness. Graceful shutdown. Swagger off in prod |
| Delivery | Multi-stage Dockerfile (layered jar, non-root, container-aware heap), docker-compose with PostgreSQL |

## Limitations

- **Not verified against real PostgreSQL in this environment** (no Docker on the build machine). The migration avoids vendor-specific SQL, and every test passes on H2 in PostgreSQL mode. The first CI step to add is a Testcontainers PostgreSQL job.
- In-flight orchestrator run state lives in one JVM. Runs aren't resumed after a restart, and two app instances don't share runs. Links, clicks, idempotency and audit **are** shared via the database.
- The rate limiter is per node.
- Scheduled jobs (retention purge) run on every node. They're idempotent, but ShedLock would avoid the duplicate work.
- Agents verify and test the existing code and don't open PRs or commit. A Git/CI integration would hang off `release`.
- Clicks are written synchronously on redirect, one INSERT per redirect.

## Next steps

1. Testcontainers PostgreSQL in CI; Redis-backed rate limiting; ShedLock for scheduled jobs.
2. Click events to Kafka (outbox), analytics pre-aggregated by a consumer, so the redirect path does no DB write.
3. Durable run state (event-sourced from the audit table, or Temporal) so runs resume after restart and scale horizontally.
4. OpenTelemetry tracing across product and control plane; SLOs on redirect p99 and orchestrator success rate.
