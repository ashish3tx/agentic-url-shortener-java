package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.url.domain.Caller;
import com.agentic.shortener.url.domain.CreateLinkCommand;
import com.agentic.shortener.url.error.AliasConflictException;
import com.agentic.shortener.url.error.InvalidLinkException;
import com.agentic.shortener.url.error.LinkGoneException;
import com.agentic.shortener.url.repository.InMemoryIdempotencyStore;
import com.agentic.shortener.url.repository.InMemoryUrlRepository;
import com.agentic.shortener.url.service.CodeGenerator;
import com.agentic.shortener.url.service.LinkValidator;
import com.agentic.shortener.url.service.ShortenerService;
import com.agentic.shortener.url.service.VisitorHasher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Executable contract checks the testing agent runs against the real service code in an
 * isolated sandbox (in-memory repositories, fixed clocks), so it never touches the production database.
 */
@Component
public class ContractTestSuite {

    public record CaseResult(String name, boolean passed, String detail) {
    }

    private record ContractCase(String name, boolean smoke, Runnable check) {
    }

    private final LinkValidator validator;
    private final CodeGenerator codeGenerator;
    private final VisitorHasher visitorHasher;
    private final Clock clock;

    public ContractTestSuite(LinkValidator validator, CodeGenerator codeGenerator, VisitorHasher visitorHasher, Clock clock) {
        this.validator = validator;
        this.codeGenerator = codeGenerator;
        this.visitorHasher = visitorHasher;
        this.clock = clock;
    }

    public List<CaseResult> run(boolean smokeOnly) {
        var results = new ArrayList<CaseResult>();
        for (var contractCase : cases()) {
            if (smokeOnly && !contractCase.smoke()) {
                continue;
            }
            try {
                contractCase.check().run();
                results.add(new CaseResult(contractCase.name(), true, "ok"));
            } catch (RuntimeException | AssertionError e) {
                results.add(new CaseResult(contractCase.name(), false, e.getMessage()));
            }
        }
        return results;
    }

    private List<ContractCase> cases() {
        return List.of(
                new ContractCase("create_and_redirect", true, () -> {
                    var service = sandbox(clock);
                    var link = service.create(CreateLinkCommand.of("https://example.com/path")).link();
                    expect("https://example.com/path".equals(service.resolve(link.code(), "203.0.113.10", "contract", null)),
                            "redirect destination mismatch");
                }),
                new ContractCase("analytics_counts_clicks", false, () -> {
                    var service = sandbox(clock);
                    var code = service.create(CreateLinkCommand.of("https://example.com/a")).link().code();
                    service.resolve(code, "203.0.113.10", "contract", "https://ref.example");
                    service.resolve(code, "203.0.113.11", "contract", null);
                    var summary = service.analytics(code, Caller.system());
                    expect(summary.totalClicks() == 2 && summary.uniqueVisitors() == 2, "analytics did not record both clicks");
                }),
                new ContractCase("rejects_private_destination", true,
                        () -> expectRejected(() -> sandbox(clock).create(CreateLinkCommand.of("http://192.168.1.4")))),
                new ContractCase("rejects_unsafe_scheme", true,
                        () -> expectRejected(() -> sandbox(clock).create(CreateLinkCommand.of("javascript:alert(1)")))),
                new ContractCase("alias_is_unique", false, () -> {
                    var service = sandbox(clock);
                    service.create(new CreateLinkCommand("https://example.com", "promo", null, "a", null));
                    try {
                        service.create(new CreateLinkCommand("https://example.org", "promo", null, "b", null));
                        throw new AssertionError("duplicate alias accepted");
                    } catch (AliasConflictException expected) {
                        // contract holds
                    }
                }),
                new ContractCase("idempotent_replay", false, () -> {
                    var service = sandbox(clock);
                    var command = new CreateLinkCommand("https://example.com/x", null, null, "client", "key-1");
                    var first = service.create(command);
                    var replay = service.create(command);
                    expect(first.created() && !replay.created() && first.link().code().equals(replay.link().code()),
                            "idempotent replay created a second link");
                }),
                new ContractCase("expired_link_is_gone", false, () -> {
                    var repository = new InMemoryUrlRepository();
                    var start = Clock.fixed(clock.instant(), ZoneOffset.UTC);
                    var code = sandbox(repository, start)
                            .create(new CreateLinkCommand("https://example.com", null, 60L, "c", null)).link().code();
                    var later = Clock.offset(start, Duration.ofMinutes(2));
                    try {
                        sandbox(repository, later).resolve(code, "203.0.113.10", "contract", null);
                        throw new AssertionError("expired link still redirected");
                    } catch (LinkGoneException expected) {
                        // contract holds
                    }
                }));
    }

    private ShortenerService sandbox(Clock sandboxClock) {
        return sandbox(new InMemoryUrlRepository(), sandboxClock);
    }

    private ShortenerService sandbox(InMemoryUrlRepository repository, Clock sandboxClock) {
        return new ShortenerService(repository, new InMemoryIdempotencyStore(), validator, codeGenerator, visitorHasher, sandboxClock);
    }

    private static void expect(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }

    private static void expectRejected(Runnable call) {
        try {
            call.run();
            throw new AssertionError("unsafe destination was accepted");
        } catch (InvalidLinkException expected) {
            // contract holds
        }
    }
}
