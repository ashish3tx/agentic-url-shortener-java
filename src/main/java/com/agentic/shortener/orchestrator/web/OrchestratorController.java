package com.agentic.shortener.orchestrator.web;

import com.agentic.shortener.orchestrator.engine.OrchestratorEngine;
import com.agentic.shortener.orchestrator.engine.RunSummary;
import com.agentic.shortener.orchestrator.engine.RunView;
import com.agentic.shortener.orchestrator.graph.ScenarioCatalog;
import com.agentic.shortener.orchestrator.model.Scenario;
import com.agentic.shortener.orchestrator.telemetry.ReliabilityMetrics;
import com.agentic.shortener.orchestrator.telemetry.TraceEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/orchestrator")
@Tag(name = "SDLC orchestrator")
public class OrchestratorController {

    private final OrchestratorEngine engine;
    private final ScenarioCatalog catalog;

    public OrchestratorController(OrchestratorEngine engine, ScenarioCatalog catalog) {
        this.engine = engine;
        this.catalog = catalog;
    }

    @GetMapping("/scenarios")
    @Operation(summary = "Scenario briefs and their stage graphs")
    public List<ScenarioView> scenarios() {
        return Arrays.stream(Scenario.values())
                .map(s -> new ScenarioView(s, s.title(), s.brief(), s.wellDefined(), catalog.graph(s).stages()))
                .toList();
    }

    @PostMapping("/runs")
    @Operation(summary = "Start a run; executes asynchronously (202)")
    public ResponseEntity<RunView> start(@Valid @RequestBody OrchestratorRequests.StartRun request, Authentication user) {
        var run = engine.start(request.scenario(), Boolean.TRUE.equals(request.autoApprove()), request.failureMode(), user.getName());
        return ResponseEntity.accepted().location(URI.create("/orchestrator/runs/" + run.id())).body(run);
    }

    @GetMapping("/runs")
    public List<RunSummary> list() {
        return engine.list();
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Run snapshot: stages, artifacts, lineage, approvals, decisions, metrics")
    public RunView get(@PathVariable String id) {
        return engine.get(id);
    }

    @GetMapping("/runs/{id}/trace")
    @Operation(summary = "Append-only audit trace for a run")
    public List<TraceEvent> trace(@PathVariable String id) {
        return engine.trace(id);
    }

    @PostMapping("/runs/{id}/approve")
    @Operation(summary = "Human approval of a pending checkpoint (four-eyes enforced for deploy/API changes)")
    public RunView approve(@PathVariable String id, @Valid @RequestBody OrchestratorRequests.Decision request,
                           Authentication user) {
        return engine.approve(id, request.stageId(), user.getName(), request.comment());
    }

    @PostMapping("/runs/{id}/reject")
    @Operation(summary = "Reject a pending checkpoint; triggers rollback and fails the run")
    public RunView reject(@PathVariable String id, @Valid @RequestBody OrchestratorRequests.Decision request,
                          Authentication user) {
        return engine.reject(id, request.stageId(), user.getName(), request.comment());
    }

    @PostMapping("/runs/{id}/stop")
    @Operation(summary = "Safe-stop: drain in-flight stages, start nothing new")
    public RunView stop(@PathVariable String id, Authentication user) {
        return engine.stop(id, user.getName());
    }

    @PostMapping("/runs/{id}/amend")
    @Operation(summary = "Amend an upstream artifact; downstream stages are re-planned")
    public RunView amend(@PathVariable String id, @Valid @RequestBody OrchestratorRequests.Amend request,
                         Authentication user) {
        return engine.amend(id, request.artifact(), request.changes(), user.getName(), request.reason());
    }

    @GetMapping("/metrics")
    @Operation(summary = "Reliability metrics: success rate, retries, fallbacks, rollbacks, MTTR, latency")
    public ReliabilityMetrics metrics() {
        return engine.metrics();
    }
}
