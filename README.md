# Agentic URL Shortener (Spring Boot 4.1 · JDK 21)

A prototype for **Build an Agentic Software Engineering System — URL Shortener**, in Java.

There are two planes:

| Plane | What it is |
| --- | --- |
| **Product** | A URL shortener: create, 302 redirect, analytics, PATCH, deactivate, TTL, custom aliases, idempotency keys, write-path rate limiting, SSRF/open-redirect guardrails, retention purge |
| **Control plane** | An agentic SDLC orchestrator that runs a gated DAG. It has entry/exit gates, parallel streams with join barriers, cross-stage context, decision lineage, human checkpoints with four-eyes, bounded retry → fallback → rollback, safe-stop, policy guardrails, an audit trace, reliability metrics (success rate, retries, rollbacks, MTTR, latency), and re-planning when upstream artifacts change |

Agents run multi-step work inside declared autonomy boundaries. Humans own approvals, change control and final quality.

> Ported from the Python reference in `../agentic-url-shortener`, with the gaps closed. The Python re-plan only *logged* hash changes. Here, downstream stages are actually invalidated and re-executed, and their approvals are superseded. MTTR now measures time-to-recover instead of failed-run duration. Approvals enforce separation of duties. The fallback is a real, degraded test suite whose coverage gap goes in front of the approver. The impact agent reads the live route table instead of a hard-coded list.

## Quick links (after `./mvnw spring-boot:run`)

| What | URL |
| --- | --- |
| **Swagger UI (API docs, try requests)** | **http://localhost:8080/swagger-ui.html** (redirects to `/swagger-ui/index.html`) |
| OpenAPI spec, JSON | http://localhost:8080/v3/api-docs |
| OpenAPI spec, YAML | http://localhost:8080/v3/api-docs.yaml |
| OpenAPI per group | http://localhost:8080/v3/api-docs/product · http://localhost:8080/v3/api-docs/orchestrator |
| Dashboard (see below) | http://localhost:8080/ |
| Health (public) | http://localhost:8080/actuator/health/liveness · http://localhost:8080/actuator/health/readiness |
| Prometheus metrics (role OPS, e.g. `admin`) | http://localhost:8080/actuator/prometheus |

A saved copy of the OpenAPI spec is in [`docs/openapi.json`](docs/openapi.json). The app also prints the Swagger and OpenAPI URLs when it starts.

> Swagger is **local/dev only**. The `prod` profile turns off both `/swagger-ui.html` and `/v3/api-docs`. To expose them in a non-prod shared environment, set `SPRINGDOC_APIDOCS_ENABLED=true` and `SPRINGDOC_SWAGGERUI_ENABLED=true` (Spring drops the dashes when mapping env vars).

### Using Swagger UI

1. Open http://localhost:8080/swagger-ui.html.
2. Pick an API group from the **Select a definition** dropdown (top right): *Product API (URL shortener)* or *SDLC Orchestrator*.
3. Click **Authorize**, then under `basicAuth` enter `alice` / `password` (see [local users](#setup)).
4. Expand an endpoint, click **Try it out**, edit the body, and click **Execute**.
5. To act as someone else (e.g. `bob` to approve a run), click **Authorize**, then **Logout**, and sign in again.

## Dashboard

The dashboard is a small web page bundled with the app at http://localhost:8080/ (`src/main/resources/static/index.html`). It's a visual front end for the **orchestrator** API, so you can demo the agentic workflow without typing curl commands. It is plain HTML/JS with no build step. It calls the same REST endpoints as Swagger and uses the same users and roles.

| Area | What it shows / does |
| --- | --- |
| Top bar | Sign-in (username/password) and live reliability metrics: success rate, runs, retries, rollbacks, MTTR, average end-to-end latency |
| Start run | Pick a scenario (GREENFIELD / BROWNFIELD / AMBIGUOUS) and a fault mode (NONE / TRANSIENT / PERSISTENT / HANG), then start. Needs role ENGINEER (e.g. `alice`) |
| Runs | All runs with their state; click one to open it |
| Run detail | **Approve / Reject** buttons for pending checkpoints (role APPROVER, e.g. `bob`), **Amend requirements** (triggers a re-plan), **Safe-stop**, and any warnings the approver should see |
| Stages | Every stage in execution order: state, dependencies, parallel group, autonomy, attempts/retries, fallback used |
| Decision lineage | Every gate, retry, fallback, rollback, approval, policy and re-plan decision, with actor and rationale |
| Artifacts | The JSON each agent produced (requirements, architecture, impact map, test report, release checklist, …) |

**Typical demo:**
1. Sign in as `alice` and start a GREENFIELD run.
2. Watch the stages run in parallel until the run reaches `WAITING_APPROVAL`.
3. Switch the sign-in to `bob` and click **Approve**.
4. Try the same with `carol`, who is both engineer and approver. The four-eyes rule blocks her from approving her own run.

The dashboard is for local demos. It signs in with Basic auth, so in `prod` (OIDC only, no Basic users by default) it can't sign in. A production operator UI would sign in through the OIDC provider and send Bearer tokens.

## Setup

Requirements: **JDK 21**. Maven is optional because the wrapper is included. The local profile needs no infrastructure (embedded H2).

```bash
./mvnw verify                 # compile + 89 unit/integration tests
./mvnw spring-boot:run        # local profile: H2, Basic auth, Swagger on
```

**Local users** (password `password`, local profile only):

| User | Roles | Can |
| --- | --- | --- |
| `alice` | USER, ENGINEER | manage own links; start / amend / stop runs |
| `bob` | USER, APPROVER | manage own links; approve / reject checkpoints |
| `carol` | USER, ENGINEER, APPROVER | both, but four-eyes stops her approving her own run |
| `admin` | USER, ADMIN, OPS | manage any link; Prometheus / actuator |

### Production (`prod` profile)

```bash
docker compose up --build     # PostgreSQL 17 + the app in the prod profile
```

| Setting | Env var | Notes |
| --- | --- | --- |
| Database | `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL; schema managed by Flyway (`db/migration`) |
| Auth | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | OIDC JWTs; roles from the `roles` claim (`app.security.jwt-roles-claim`) |
| Public URL | `SHORTENER_BASE_URL` | used in returned short URLs |
| IP salt | `SHORTENER_ANALYTICS_IP_SALT` | ≥ 16 chars, from a secret manager |

The `prod` profile **fails at startup** if any of these is missing or invalid, or if no authentication is configured. It also:
- turns Swagger off,
- forbids auto-approve,
- logs JSON (ECS) with a request id on every line,
- trusts `X-Forwarded-*` only via the framework strategy,
- shuts down gracefully, safe-stopping active runs.

End-to-end demo against a running server: `./scripts/demo.sh`. Single requests are in `requests.http` (IntelliJ / VS Code REST Client).

## Product API

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/v1/urls` | `{url, alias?, ttlSeconds?}` → 201 + `Location`. `Idempotency-Key` replay → 200; same key with a different payload → 422 |
| GET | `/{code}` | 302 + `Cache-Control: no-store`; records a click; 404 unknown, 410 expired/deactivated |
| GET | `/api/v1/urls/{code}` | metadata (owner identity is never returned) |
| GET | `/api/v1/urls/{code}/analytics` | total, unique visitors, by day, top referrers |
| PATCH | `/api/v1/urls/{code}` | change destination and/or TTL (re-validated) |
| DELETE | `/api/v1/urls/{code}` | deactivate → 204 |

- `/api/**` requires role USER. A link is visible and manageable only by its owner or an ADMIN (others get 403). The redirect `GET /{code}` is public.
- Writes are limited to 30/min per authenticated user. Exceeding the limit returns 429 with `Retry-After`.
- Errors are RFC 7807 `application/problem+json`. Every response carries `X-Request-Id`.

## Orchestrator API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/orchestrator/scenarios` | the three graphs (stages, deps, gates, autonomy) |
| POST | `/orchestrator/runs` | ENGINEER. `{scenario, autoApprove?, failureMode?: NONE\|TRANSIENT\|PERSISTENT\|HANG}` → 202. The initiator is the logged-in user |
| GET | `/orchestrator/runs[/{id}]` | stages, artifacts + lineage (producer, sha256, version), approvals, decisions, metrics |
| GET | `/orchestrator/runs/{id}/trace` | append-only audit events, read from the database (survives restarts) |
| POST | `/orchestrator/runs/{id}/approve` · `/reject` | APPROVER. `{stageId?, comment}`; four-eyes for `PRODUCTION_DEPLOY` / `PUBLIC_API` |
| POST | `/orchestrator/runs/{id}/stop` | ENGINEER. Safe-stop: drain in-flight, start nothing new |
| POST | `/orchestrator/runs/{id}/amend` | ENGINEER. `{artifact, changes, reason}` → re-plan downstream |
| GET | `/orchestrator/metrics` | success rate, retries, fallbacks, rollbacks, replans, MTTR, avg e2e latency |

## Scenarios

The three scenarios use different graphs, not one graph with different labels. See [docs/scenarios.md](docs/scenarios.md).

1. **GREENFIELD:** requirements → architecture → 3 parallel impl streams → join → tests ∥ docs → human-gated `PRODUCTION_DEPLOY`
2. **BROWNFIELD:** adds **impact analysis** over the live codebase before design. Release is `PUBLIC_API` change control
3. **AMBIGUOUS:** "make it enterprise-ready". Open questions are recorded and a human must **accept the bounded interpretation** before design can start (two human checkpoints)

## How to test

```bash
./mvnw test                                        # all 89 tests
./mvnw test -Dtest=OrchestratorEngineIntegrationTest  # one class
./mvnw test -Dtest='ShortenerServiceTest#customAliasIsUnique'  # one method
```

Manual testing against a running app (`./mvnw spring-boot:run`):

1. **Swagger UI:** open http://localhost:8080/swagger-ui.html. Click **Authorize**, choose `basicAuth`, enter `alice` / `password`. Then expand an operation, click **Try it out**, and **Execute**.
   - Suggested order: `POST /api/v1/urls`, then `GET /{code}` (Swagger follows the redirect, so use curl to see the 302), then `GET /api/v1/urls/{code}/analytics`.
   - For the orchestrator, as alice: `POST /orchestrator/runs` with `{"scenario":"GREENFIELD"}`, then poll `GET /orchestrator/runs/{id}` until `WAITING_APPROVAL`.
   - Then **Logout**, **Authorize** as `bob`, and `POST .../approve` with `{}`.
2. **Script:** `./scripts/demo.sh` runs the whole story with curl.
3. **REST client:** `requests.http` (IntelliJ / VS Code REST Client).
4. **Dashboard:** http://localhost:8080/ to start, approve, reject, amend and stop runs visually.

## Testing approach

| Layer | Tests | What they prove |
| --- | --- | --- |
| Unit | `LinkValidatorTest` (33 cases), `SlidingWindowRateLimiterTest`, `ShortenerServiceTest`, `StageGraphTest` | SSRF vectors incl. decimal/hex/octal IPs, IPv6 ULA and IPv4-mapped IPs. Alias races (50 concurrent claims → 1 winner), idempotency reservation (30 concurrent retries → 1 link), ownership, limiter correctness under 500 concurrent virtual threads, TTL with a movable clock, cycle detection |
| Persistence integration | `PersistenceIntegrationTest` | the same race guarantees against the **real JPA adapters + database** (PK-enforced alias uniqueness, reservation idempotency), DB-side analytics aggregation, retention purge |
| API + security integration | `ShortUrlApiIntegrationTest` | full HTTP contract, 401 anonymous writes, 403 on other users' links, admin override, Basic auth users, ProblemDetail, idempotency, 429, actuator/Prometheus exposure, OpenAPI groups |
| Orchestration integration | `OrchestratorEngineIntegrationTest`, `OrchestratorApiIntegrationTest` | all 3 scenarios end to end, join ordering, role-based access, actor taken from the principal, four-eyes, auto-approver impersonation, reject → rollback, transient → retry, **hung agent → timeout → retry**, persistent → fallback + approver warning, re-plan + approval superseded, re-plan revokes assumption acceptance, secret in amendment blocked, safe-stop, durable ordered audit trace |
| In-run validation | `ContractTestSuite` (executed by the testing *agent*) | every run actually exercises the product code in an isolated sandbox. The release gate reads that report, not a hard-coded "passed" |

## Docs

- [ARCHITECTURE.md](ARCHITECTURE.md): components, orchestration model, control flow, concurrency, key decisions
- [docs/scenarios.md](docs/scenarios.md): decomposition, orchestration and validation for each scenario
- [ENGINEERING_SUMMARY.md](ENGINEERING_SUMMARY.md): plan, artifacts, risks/trade-offs, assumptions, limitations

## Autonomy boundaries

| Agents may | Humans must |
| --- | --- |
| Normalize well-defined briefs, design, verify implementation, run tests, write docs | Accept assumptions on ambiguous work |
| Retry transient failures, fall back to a degraded suite | Approve `PUBLIC_API` and `PRODUCTION_DEPLOY` (initiator cannot self-approve) |
| Publish artifacts (policy-scanned, hashed, attributed) | Reject, safe-stop, amend scope, and own final quality |
