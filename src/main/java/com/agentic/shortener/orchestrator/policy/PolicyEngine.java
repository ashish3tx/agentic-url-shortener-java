package com.agentic.shortener.orchestrator.policy;

import com.agentic.shortener.common.Hashing;
import com.agentic.shortener.config.OrchestratorProperties;
import com.agentic.shortener.orchestrator.error.PolicyViolationException;
import com.agentic.shortener.orchestrator.graph.StageGraph;
import com.agentic.shortener.orchestrator.model.Approval;
import com.agentic.shortener.orchestrator.model.Autonomy;
import com.agentic.shortener.orchestrator.model.StageKind;
import com.agentic.shortener.orchestrator.model.StageSpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Security, compliance and change-control guardrails. The engine calls these at fixed points;
 * agents cannot opt out, and a violation stops the stage without retry.
 */
@Component
public class PolicyEngine {

    public static final String AUTO_APPROVER = "auto-approve";

    private static final Pattern SECRET = Pattern.compile(
            "(api[_-]?key|password|passwd|secret|token|authorization|private[_-]?key)\"?\\s*[:=]\\s*\"?[^\\s\",}]{4,}",
            Pattern.CASE_INSENSITIVE);

    private final JsonMapper canonicalJson;
    private final OrchestratorProperties.Governance governance;

    public PolicyEngine(JsonMapper jsonMapper, OrchestratorProperties properties) {
        // Sorted keys give a stable hash regardless of map insertion order.
        this.canonicalJson = jsonMapper.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        this.governance = properties.governance();
    }

    /**
     * Structural rules every graph must satisfy before it may run.
     */
    public void checkGraph(StageGraph graph) {
        graph.stages().forEach(this::checkStageStart);
    }

    public void checkStageStart(StageSpec spec) {
        if (spec.kind() == StageKind.RELEASE && (spec.autonomy() != Autonomy.HUMAN_GATED || !spec.requiresApproval())) {
            throw new PolicyViolationException("release_requires_human",
                    "release stage '" + spec.id() + "' must be human-gated with an approval checkpoint");
        }
        if (spec.autonomy() == Autonomy.HUMAN_GATED && !spec.requiresApproval()) {
            throw new PolicyViolationException("human_gate_without_approval",
                    "stage '" + spec.id() + "' is human-gated but declares no approval kind");
        }
    }

    public void checkRunStart(boolean autoApprove, String initiator) {
        requireActor(initiator);
        if (autoApprove && !governance.autoApproveAllowed()) {
            throw new PolicyViolationException("auto_approve_disabled", "auto-approve is disabled in this environment");
        }
    }

    public void checkArtifact(String name, Map<String, Object> content) {
        if (SECRET.matcher(toJson(content)).find()) {
            throw new PolicyViolationException("no_secrets_in_artifacts", "artifact '" + name + "' appears to contain a credential");
        }
    }

    public void checkApprover(Approval approval, String actor, String initiator, boolean autoApproveRun) {
        requireActor(actor);
        if (AUTO_APPROVER.equals(actor)) {
            if (!autoApproveRun) {
                throw new PolicyViolationException("auto_approver_reserved", "'" + AUTO_APPROVER + "' may only approve auto-approve runs");
            }
            return;
        }
        if (governance.fourEyes() && approval.kind().requiresFourEyes() && actor.equalsIgnoreCase(initiator)) {
            throw new PolicyViolationException("four_eyes",
                    approval.kind() + " must be approved by someone other than the run initiator");
        }
    }

    public void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new PolicyViolationException("actor_required", "an identified actor is required for audit");
        }
    }

    public String fingerprint(Map<String, Object> content) {
        return Hashing.sha256(toJson(content)).substring(0, 16);
    }

    private String toJson(Map<String, Object> content) {
        return canonicalJson.writeValueAsString(content);
    }
}
