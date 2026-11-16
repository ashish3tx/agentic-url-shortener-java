package com.agentic.shortener.url;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.hasKey;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shortener.rate-limit.requests=5")
@AutoConfigureMockMvc
class ShortUrlApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void createRedirectAndReadAnalytics() throws Exception {
        var owner = newUser();
        var code = "docs-" + suffix();
        mvc.perform(json(post("/api/v1/urls"), owner).content("""
                        {"url":"https://example.com/docs","alias":"%s"}""".formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/urls/" + code))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/" + code))
                .andExpect(jsonPath("$.createdBy").doesNotExist());

        mvc.perform(get("/" + code).header("Referer", "https://news.example"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/docs"))
                .andExpect(header().string("Cache-Control", "no-store"));

        mvc.perform(get("/api/v1/urls/{code}/analytics", code).with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.topReferrers['https://news.example']").value(1));
    }

    @Test
    void writesRequireAuthenticationAndLinksArePrivateToTheirOwner() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("""
                        {"url":"https://example.com"}"""))
                .andExpect(status().isUnauthorized());

        var code = "own-" + suffix();
        mvc.perform(json(post("/api/v1/urls"), newUser()).content("""
                {"url":"https://example.com","alias":"%s"}""".formatted(code))).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/urls/{code}", code).with(newUser())).andExpect(status().isForbidden());
        mvc.perform(json(delete("/api/v1/urls/{code}", code), newUser())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/urls/{code}", code).with(user("root").roles("USER", "ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/" + code)).andExpect(status().isFound());
    }

    @Test
    void configuredBasicAuthUsersWork() throws Exception {
        mvc.perform(get("/api/v1/urls/{code}", "missing-" + suffix()).with(httpBasic("alice", "password")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/urls/{code}", "missing").with(httpBasic("alice", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void idempotencyKeyReplaysWith200() throws Exception {
        var owner = newUser();
        var body = """
                {"url":"https://example.com/replay"}""";
        var first = mvc.perform(json(post("/api/v1/urls"), owner).header("Idempotency-Key", "abc").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(first, "$.code");

        mvc.perform(json(post("/api/v1/urls"), owner).header("Idempotency-Key", "abc").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
        mvc.perform(json(post("/api/v1/urls"), owner).header("Idempotency-Key", "abc").content("""
                        {"url":"https://example.com/other"}"""))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void unsafeDestinationIsProblemDetail400() throws Exception {
        mvc.perform(json(post("/api/v1/urls"), newUser()).content("""
                        {"url":"http://169.254.169.254/latest/meta-data"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.field").value("url"));
    }

    @Test
    void beanValidationErrorsListFields() throws Exception {
        mvc.perform(json(post("/api/v1/urls"), newUser()).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("url")));
    }

    @Test
    void patchDeleteAndGoneLifecycle() throws Exception {
        var owner = newUser();
        var alias = "life-" + suffix();
        mvc.perform(json(post("/api/v1/urls"), owner).content("""
                {"url":"https://example.com","alias":"%s"}""".formatted(alias))).andExpect(status().isCreated());

        mvc.perform(json(patch("/api/v1/urls/{code}", alias), owner).content("""
                        {"url":"https://example.org/v2","ttlSeconds":3600}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destination").value("https://example.org/v2"))
                .andExpect(jsonPath("$.expiresAt").exists());

        mvc.perform(json(delete("/api/v1/urls/{code}", alias), owner)).andExpect(status().isNoContent());
        mvc.perform(get("/" + alias)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/urls/{code}", "nope-" + suffix()).with(owner)).andExpect(status().isNotFound());
    }

    @Test
    void duplicateAliasIs409() throws Exception {
        var owner = newUser();
        var body = """
                {"url":"https://example.com","alias":"dup-%s"}""".formatted(suffix());
        mvc.perform(json(post("/api/v1/urls"), owner).content(body)).andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/urls"), owner).content(body)).andExpect(status().isConflict());
    }

    @Test
    void writesAreRateLimitedPerUserButReadsAreNot() throws Exception {
        var owner = newUser();
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/api/v1/urls"), owner).content("""
                    {"url":"https://example.com/%d"}""".formatted(i))).andExpect(status().isCreated());
        }
        mvc.perform(json(post("/api/v1/urls"), owner).content("""
                        {"url":"https://example.com/6"}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(json(post("/api/v1/urls"), newUser()).content("""
                {"url":"https://example.com/other-client"}""")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/urls/{code}", "unknown-1").with(owner)).andExpect(status().isNotFound());
    }

    @Test
    void opsEndpointsAndDocsHaveTheRightExposure() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(newUser())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(user("ops").roles("OPS"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(header().exists("X-Request-Id"));

        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/urls']").exists())
                .andExpect(jsonPath("$.paths['/orchestrator/runs']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth").exists());
        mvc.perform(get("/v3/api-docs/product")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/urls']").exists())
                .andExpect(jsonPath("$.paths['/orchestrator/runs']").doesNotExist());
        mvc.perform(get("/v3/api-docs/orchestrator")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/orchestrator/runs']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/urls']").doesNotExist());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.urls.length()").value(2));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, RequestPostProcessor caller) {
        return builder.with(caller).contentType(MediaType.APPLICATION_JSON);
    }

    private static RequestPostProcessor newUser() {
        return user("user-" + suffix()).roles("USER");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
