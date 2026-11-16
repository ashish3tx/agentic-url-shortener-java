package com.agentic.shortener.orchestrator.web;

import com.agentic.shortener.orchestrator.model.FailureMode;
import com.agentic.shortener.orchestrator.model.Scenario;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request bodies for the orchestrator API. The acting user always comes from the authenticated principal.
 */
public final class OrchestratorRequests {

    private OrchestratorRequests() {
    }

    public record StartRun(
            @NotNull @Schema(example = "GREENFIELD") Scenario scenario,
            @Schema(description = "Demo only; defaults to false") Boolean autoApprove,
            @Schema(description = "Fault injection for demos", example = "NONE") FailureMode failureMode) {
    }

    public record Decision(
            @Schema(description = "Optional when exactly one approval is pending") String stageId,
            @Schema(example = "CHG-1001 reviewed") String comment) {
    }

    public record Amend(
            @NotBlank @Schema(example = "requirements") String artifact,
            @NotEmpty Map<String, Object> changes,
            @NotBlank String reason) {
    }
}
