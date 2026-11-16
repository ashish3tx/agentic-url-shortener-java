package com.agentic.shortener.orchestrator.gate;

import com.agentic.shortener.orchestrator.model.GateResult;
import com.agentic.shortener.orchestrator.model.RunContext;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Entry/exit predicates over the shared run context. A stage cannot start (entry) or be marked
 * done (exit) unless its gates pass; results are recorded in the decision lineage.
 */
public enum Gate {

    REQUIREMENTS_COMPLETE {
        @Override
        public GateResult evaluate(RunContext ctx) {
            var requirements = ctx.artifact("requirements");
            if (requirements.isEmpty()) {
                return fail("requirements artifact missing");
            }
            var missing = List.of("problem", "acceptanceCriteria", "assumptions").stream()
                    .filter(field -> !requirements.get().containsKey(field))
                    .toList();
            return missing.isEmpty() ? pass("requirements normalized") : fail("requirements missing " + missing);
        }
    },

    AMBIGUITY_RESOLVED {
        @Override
        public GateResult evaluate(RunContext ctx) {
            var openQuestions = ctx.artifact("requirements").map(r -> r.get("openQuestions")).orElse(List.of());
            boolean hasOpen = openQuestions instanceof Collection<?> c && !c.isEmpty();
            if (hasOpen && !ctx.assumptionsAccepted()) {
                return fail("open questions remain; a human must accept the bounded interpretation");
            }
            return pass(hasOpen ? "bounded interpretation accepted by a human" : "no open questions");
        }
    },

    IMPACT_MAPPED {
        @Override
        public GateResult evaluate(RunContext ctx) {
            var components = ctx.artifact("impact").map(i -> i.get("impactedComponents")).orElse(null);
            return components instanceof Map<?, ?> m && !m.isEmpty()
                    ? pass("impacted components identified")
                    : fail("brownfield impact map missing");
        }
    },

    ARCHITECTURE_PRESENT {
        @Override
        public GateResult evaluate(RunContext ctx) {
            return ctx.artifact("architecture").filter(a -> a.containsKey("components")).isPresent()
                    ? pass("architecture available to implementers")
                    : fail("architecture artifact missing");
        }
    },

    IMPLEMENTATION_COMPLETE {
        @Override
        public GateResult evaluate(RunContext ctx) {
            var status = ctx.artifact("implementation").map(i -> i.get("status")).orElse(null);
            return "ok".equals(status)
                    ? pass("all implementation streams verified")
                    : fail("implementation did not report ok");
        }
    },

    TESTS_GREEN {
        @Override
        public GateResult evaluate(RunContext ctx) {
            var tests = ctx.artifact("tests");
            if (tests.isEmpty()) {
                return fail("test report missing");
            }
            return tests.get().get("failed") instanceof Number failed && failed.intValue() == 0
                    ? pass("contract tests passed")
                    : fail("contract tests failed: " + tests.get().get("failures"));
        }
    },

    DOCS_PRESENT {
        @Override
        public GateResult evaluate(RunContext ctx) {
            return ctx.artifact("documentation").isPresent()
                    ? pass("documentation generated")
                    : fail("documentation artifact missing");
        }
    },

    NO_POLICY_VIOLATIONS {
        @Override
        public GateResult evaluate(RunContext ctx) {
            var violations = ctx.policyViolations();
            return violations.isEmpty() ? pass("no policy violations") : fail("policy violations: " + violations);
        }
    },

    RELEASE_READY {
        @Override
        public GateResult evaluate(RunContext ctx) {
            var checklist = ctx.artifact("release").map(r -> r.get("checklist")).orElse(null);
            if (!(checklist instanceof Map<?, ?> items) || items.isEmpty()) {
                return fail("release checklist missing");
            }
            var unmet = items.entrySet().stream()
                    .filter(e -> !Boolean.TRUE.equals(e.getValue()))
                    .map(e -> String.valueOf(e.getKey()))
                    .toList();
            return unmet.isEmpty()
                    ? pass("release checklist complete; awaiting change-control approval")
                    : fail("release checklist unmet: " + unmet);
        }
    };

    public abstract GateResult evaluate(RunContext ctx);

    GateResult pass(String reason) {
        return new GateResult(this, true, reason);
    }

    GateResult fail(String reason) {
        return new GateResult(this, false, reason);
    }
}
