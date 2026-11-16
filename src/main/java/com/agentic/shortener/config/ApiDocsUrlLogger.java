package com.agentic.shortener.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** Prints where the API docs live once the real port is known (works with server.port=0 too). */
@Component
public class ApiDocsUrlLogger implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ApiDocsUrlLogger.class);

    private final String swaggerPath;
    private final String apiDocsPath;

    public ApiDocsUrlLogger(@Value("${springdoc.swagger-ui.path:/swagger-ui.html}") String swaggerPath,
                            @Value("${springdoc.api-docs.path:/v3/api-docs}") String apiDocsPath) {
        this.swaggerPath = swaggerPath;
        this.apiDocsPath = apiDocsPath;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        var base = "http://localhost:" + event.getWebServer().getPort();
        log.info("""

                ----------------------------------------------------------
                  Swagger UI : {}{}
                  OpenAPI    : {}{}  (groups: {}/product, {}/orchestrator)
                  Dashboard  : {}/
                ----------------------------------------------------------""",
                base, swaggerPath, base, apiDocsPath, apiDocsPath, apiDocsPath, base);
    }
}
