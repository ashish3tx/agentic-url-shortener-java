package com.agentic.shortener.orchestrator;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrchestratorApiIntegrationTest {

    private static final RequestPostProcessor ALICE = user("alice").roles("ENGINEER");
    private static final RequestPostProcessor BOB = user("bob").roles("APPROVER");
    private static final RequestPostProcessor CAROL = user("carol").roles("ENGINEER", "APPROVER");

    @Autowired
    private MockMvc mvc;

    @Test
    void actorComesFromTheAuthenticatedPrincipal() throws Exception {
        var id = startRun(ALICE, "GREENFIELD");
        awaitState(id, "WAITING_APPROVAL");

        mvc.perform(get("/orchestrator/runs/{id}", id).with(BOB)).andExpect(jsonPath("$.initiator").value("alice"));
        mvc.perform(post("/orchestrator/runs/{id}/approve", id).with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/orchestrator/runs/{id}/approve", id).with(BOB).contentType(MediaType.APPLICATION_JSON).content("""
                        {"comment":"CHG-1"}"""))
                .andExpect(status().isOk());

        awaitState(id, "SUCCEEDED");
        mvc.perform(get("/orchestrator/runs/{id}", id).with(BOB))
                .andExpect(jsonPath("$.approvals[0].actor").value("bob"));
        mvc.perform(get("/orchestrator/runs/{id}/trace", id).with(BOB)).andExpect(jsonPath("$[0].event").value("run_started"));
        mvc.perform(get("/orchestrator/metrics").with(BOB)).andExpect(jsonPath("$.runsSucceeded").isNumber());
    }

    @Test
    void fourEyesBlocksAnInitiatorWhoIsAlsoAnApprover() throws Exception {
        var id = startRun(CAROL, "BROWNFIELD");
        awaitState(id, "WAITING_APPROVAL");

        mvc.perform(post("/orchestrator/runs/{id}/approve", id).with(CAROL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rule").value("four_eyes"));
    }

    @Test
    void rolesGuardEveryOperation() throws Exception {
        mvc.perform(get("/orchestrator/runs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/orchestrator/runs").with(BOB).contentType(MediaType.APPLICATION_JSON).content("""
                        {"scenario":"GREENFIELD"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(get("/orchestrator/runs").with(user("someone").roles("USER"))).andExpect(status().isForbidden());
    }

    @Test
    void errorsMapToProblemDetails() throws Exception {
        mvc.perform(get("/orchestrator/runs/{id}", "missing").with(ALICE)).andExpect(status().isNotFound());
        mvc.perform(get("/orchestrator/runs/{id}/trace", "missing").with(ALICE)).andExpect(status().isNotFound());
        mvc.perform(post("/orchestrator/runs").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.scenario").exists());
        mvc.perform(post("/orchestrator/runs").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"scenario":"nope"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scenariosExposeTheirGraphs() throws Exception {
        mvc.perform(get("/orchestrator/scenarios").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].stages[1].id").value("impact"));
    }

    private String startRun(RequestPostProcessor initiator, String scenario) throws Exception {
        var body = mvc.perform(post("/orchestrator/runs").with(initiator).contentType(MediaType.APPLICATION_JSON).content("""
                        {"scenario":"%s"}""".formatted(scenario)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void awaitState(String id, String state) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> mvc.perform(get("/orchestrator/runs/{id}", id).with(BOB))
                .andExpect(jsonPath("$.state").value(state)));
    }
}
