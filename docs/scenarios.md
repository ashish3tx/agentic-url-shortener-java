# Scenarios

Each scenario is its own graph (`ScenarioGraphs`). Start one with:

- the dashboard,
- `POST /orchestrator/runs {"scenario":"…","initiator":"alice"}`, or
- `./scripts/demo.sh`.

Every run's `decisions[]` and `/trace` show the decomposition and execution as they happen.

---

## 1. Greenfield: well-defined new system

**Intent.** "Build a URL shortener service from scratch with core APIs, analytics, and reliability features."

### Decomposition

| Stage | Agent | Why it exists | Gates |
| --- | --- | --- | --- |
| requirements | `requirements` (FULL) | Normalize the brief into problem, 6 acceptance criteria, NFRs, assumptions and out-of-scope | exit `REQUIREMENTS_COMPLETE` |
| architecture | `architecture` | Components (real class names), data model, ADR-1/ADR-2. Records the requirements hash it was designed against | entry `REQUIREMENTS_COMPLETE`, exit `ARCHITECTURE_PRESENT` |
| impl_api ∥ impl_store ∥ impl_analytics | probes | Independent slices; run **in parallel** | entry `ARCHITECTURE_PRESENT` |
| sync_impl | `integration_sync` (FULL) | Join barrier (`ALL`). Refuses a partial merge | exit `IMPLEMENTATION_COMPLETE` |
| testing ∥ documentation | `contract_tests` / `documentation` | Run in parallel after the join. Docs don't wait for tests | exit `TESTS_GREEN` / `DOCS_PRESENT` |
| release | `release` (HUMAN_GATED) | Go/no-go checklist, then **`PRODUCTION_DEPLOY` approval (four-eyes)** | entry `TESTS_GREEN, DOCS_PRESENT, NO_POLICY_VIOLATIONS`, exit `RELEASE_READY` |

### Orchestration

The graph is non-linear: a 3-way fan-out, a join, then a 2-way fan-out joined at release. `joinStageWaitsForEveryParallelStream` checks that the join started only after every stream ended.

### Validation

- The contract suite runs inside the run: create + redirect, analytics counting, private-IP and `javascript:` rejection, alias uniqueness, idempotent replay, expiry → 410.
- `failureMode=TRANSIENT` fails the suite once, which shows a **retry** in the lineage.
- `PERSISTENT` exhausts 3 attempts and falls back to the **smoke suite**. The approver sees a "degraded coverage" warning.

---

## 2. Brownfield: enhance an existing system

**Intent.** Add custom aliases, TTL expiry, PATCH and per-identity write rate limits *without breaking redirects*.

### Decomposition

| Stage | Why it exists |
| --- | --- |
| requirements | Additive change; 5 acceptance criteria including "GET /{code} stays 302" |
| **impact** | **Codebase reasoning against the running app.** Reads the live `RequestMappingHandlerMapping` (actual routes → controller#method) and reflects over the public surface of `ShortUrlController`, `ShortenerService`, `LinkValidator`, `SlidingWindowRateLimiter`, `UrlRepository`. Emits data flow, API delta, regression risks and untouched areas |
| architecture | Designs against the impact map (`entry: IMPACT_MAPPED`). ADR-3: additive API evolution |
| impl_alias ∥ impl_ttl ∥ impl_ratelimit | Probes: reserved aliases rejected, TTL bounds, limiter isolates identities |
| sync → testing ∥ docs | Regression and new behaviour. Docs include a changelog generated from the impact `apiDelta` |
| release | **`PUBLIC_API` change control** (four-eyes) instead of a generic deploy |

### Orchestration

There's an extra analysis node ahead of design, and its approval kind is API change control. If the impact agent finds no product endpoints it fails, which blocks design.

### Validation

`IMPACT_MAPPED` gate. The redirect contract stays in the suite. `ShortUrlApiIntegrationTest` covers PATCH/DELETE/410 and 429 + `Retry-After`. `rejectionRollsBackRelease` shows a reviewer rejecting the API change, which triggers the rollback agent and a `NO_GO`.

---

## 3. Ambiguous: "make it enterprise-ready"

**Intent.** *"Make the URL shortener enterprise-ready for a financial-services context."* That is not a specification.

### What the requirements agent does (and doesn't do)

It doesn't invent SSO, tenancy, or a regulatory programme. It records:

- **Ambiguities:** identity model, retention period, regulatory overlay (SOX/GLBA/SEC 17a-4), tenant isolation
- **Open questions:** SSO vs API key? retention window? per-tenant allow-lists?
- **Proposed bounded interpretation (assumptions):**
  - API-key/IP identity
  - 30-day click retention with a scheduled purge
  - default-deny unsafe destinations
  - four-eyes on production deploy

### Decomposition

| Stage | Why it exists |
| --- | --- |
| requirements (SUPERVISED) | Discover ambiguity; propose a bounded scope |
| **normalize** (HUMAN_GATED, `ASSUMPTION_ACCEPTANCE`) | A human accepts or rejects the interpretation. Approval sets `assumptionsAccepted` |
| architecture | Blocked by `AMBIGUITY_RESOLVED` until accepted. ADR-3 lists the accepted assumptions |
| impl_ssrf ∥ impl_audit ∥ impl_retention | SSRF probe fires 9 hostile vectors (metadata IP, `[::1]`, decimal IP, userinfo, …). Audit checks no raw IPs are kept. Retention checks the purge job and that the window is ≤ 30 days |
| testing ∥ documentation | Docs carry deferred questions and residual risks (DNS rebinding, no SSO, single node) |
| release | `PRODUCTION_DEPLOY`, four-eyes |

### Orchestration

The run has two human checkpoints. If scope changes after acceptance (`POST /amend` on `requirements`, e.g. "retention is 90 days"), the engine **re-plans**:

- `normalize` and everything after it is reset,
- the acceptance is revoked and its approval superseded,
- architecture is blocked again until a human re-accepts.

`replanAfterAssumptionAcceptanceRequiresAcceptanceAgain` covers this.

### Validation

- The `impl_ssrf` stage fails if any hostile vector is accepted.
- A reviewer can reject at either checkpoint.
- An amendment containing a credential is blocked by policy (`no_secrets_in_artifacts`) and never stored.
