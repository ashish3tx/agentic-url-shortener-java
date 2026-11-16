package com.agentic.shortener.orchestrator.web;

import com.agentic.shortener.orchestrator.model.Scenario;
import com.agentic.shortener.orchestrator.model.StageSpec;

import java.util.List;

public record ScenarioView(Scenario scenario, String title, String brief, boolean wellDefined, List<StageSpec> stages) {
}
