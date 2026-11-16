package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.model.StageContext;

import java.util.function.Consumer;

/**
 * A specialised worker for one kind of SDLC task. Agents only read the shared context and
 * publish artifacts; they never change run state, so the engine keeps full control of flow.
 * Throwing signals failure and lets the engine apply retry/fallback/rollback policy.
 */
public interface Agent {

    String id();

    void execute(StageContext ctx);

    static Agent of(String id, Consumer<StageContext> body) {
        return new SimpleAgent(id, body);
    }

    record SimpleAgent(String id, Consumer<StageContext> body) implements Agent {
        @Override
        public void execute(StageContext ctx) {
            body.accept(ctx);
        }
    }
}
