package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.model.FailureMode;
import com.agentic.shortener.orchestrator.model.StageContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;

/**
 * Primary agent runs the full contract suite. The fallback runs the deterministic smoke subset
 * and marks coverage as degraded, which surfaces as a warning on the release approval so a
 * human, not the agent, decides whether reduced coverage is acceptable.
 */
@Configuration(proxyBeanMethods = false)
public class TestingAgents {

    @Bean
    Agent contractTests(ContractTestSuite suite) {
        return Agent.of("contract_tests", ctx -> {
            int run = ctx.nextContractSuiteRun();
            if (ctx.failureMode() == FailureMode.HANG && run == 1) {
                hang();
            }
            var results = new ArrayList<>(suite.run(false));
            if (injectFailure(ctx.failureMode(), run)) {
                results.add(new ContractTestSuite.CaseResult("harness_stability", false, "injected " + ctx.failureMode() + " failure"));
            }
            publishReport(ctx, "contract", results, false);
        });
    }

    @Bean
    Agent smokeTests(ContractTestSuite suite) {
        return Agent.of("smoke_tests", ctx -> publishReport(ctx, "smoke", suite.run(true), true));
    }

    private static boolean injectFailure(FailureMode mode, int run) {
        return switch (mode) {
            case NONE, HANG -> false;
            case TRANSIENT -> run == 1;
            case PERSISTENT -> true;
        };
    }

    private static void hang() {
        try {
            Thread.sleep(Duration.ofMinutes(5));
        } catch (InterruptedException e) {
            // The engine cancelled us after the stage timeout.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("contract suite interrupted after hanging");
        }
    }

    private static void publishReport(StageContext ctx, String suite, List<ContractTestSuite.CaseResult> results, boolean degraded) {
        var failures = results.stream().filter(r -> !r.passed()).map(r -> r.name() + ": " + r.detail()).toList();
        ctx.publish("tests", ordered(
                "suite", suite,
                "passed", results.size() - failures.size(),
                "failed", failures.size(),
                "failures", failures,
                "cases", results.stream().map(ContractTestSuite.CaseResult::name).toList(),
                "degradedCoverage", degraded));
        if (!failures.isEmpty()) {
            throw new IllegalStateException(suite + " tests failed: " + failures);
        }
    }
}
