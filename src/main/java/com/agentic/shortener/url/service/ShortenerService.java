package com.agentic.shortener.url.service;

import com.agentic.shortener.url.domain.AnalyticsSummary;
import com.agentic.shortener.url.domain.Caller;
import com.agentic.shortener.url.domain.ClickEvent;
import com.agentic.shortener.url.domain.CreateLinkCommand;
import com.agentic.shortener.url.domain.CreateResult;
import com.agentic.shortener.url.domain.ShortUrl;
import com.agentic.shortener.url.error.AliasConflictException;
import com.agentic.shortener.url.error.IdempotencyConflictException;
import com.agentic.shortener.url.error.IdempotencyInProgressException;
import com.agentic.shortener.url.error.LinkAccessDeniedException;
import com.agentic.shortener.url.error.LinkGoneException;
import com.agentic.shortener.url.error.LinkNotFoundException;
import com.agentic.shortener.url.repository.IdempotencyStore;
import com.agentic.shortener.url.repository.UrlRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;

@Service
public class ShortenerService {

    private static final int MAX_CODE_ATTEMPTS = 10;
    private static final int TOP_REFERRERS = 20;
    private static final int MAX_HEADER_LENGTH = 256;

    private final UrlRepository repository;
    private final IdempotencyStore idempotency;
    private final LinkValidator validator;
    private final CodeGenerator codeGenerator;
    private final VisitorHasher visitorHasher;
    private final Clock clock;

    public ShortenerService(UrlRepository repository, IdempotencyStore idempotency, LinkValidator validator,
                            CodeGenerator codeGenerator, VisitorHasher visitorHasher, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.validator = validator;
        this.codeGenerator = codeGenerator;
        this.visitorHasher = visitorHasher;
        this.clock = clock;
    }

    public CreateResult create(CreateLinkCommand command) {
        var key = command.idempotencyKey();
        if (key == null || key.isBlank()) {
            return new CreateResult(createLink(command), true);
        }
        // Keys are scoped per caller so one client can never replay another client's link.
        var scopedKey = command.createdBy() + ":" + key;
        var fingerprint = command.payloadFingerprint();
        if (!idempotency.reserve(scopedKey, fingerprint, clock.instant())) {
            return replay(scopedKey, fingerprint);
        }
        try {
            var link = createLink(command);
            idempotency.complete(scopedKey, link.code());
            return new CreateResult(link, true);
        } catch (RuntimeException e) {
            idempotency.release(scopedKey);
            throw e;
        }
    }

    public String resolve(String code, String clientIp, String userAgent, String referrer) {
        var link = requireActive(findOrThrow(code));
        repository.recordClick(new ClickEvent(
                code,
                clock.instant(),
                visitorHasher.hash(clientIp),
                truncate(userAgent),
                referrer == null || referrer.isBlank() ? "direct" : truncate(referrer)));
        return link.destination();
    }

    public ShortUrl get(String code, Caller caller) {
        return requireOwner(findOrThrow(code), caller);
    }

    public ShortUrl update(String code, String newDestination, Long ttlSeconds, Caller caller) {
        requireActive(requireOwner(findOrThrow(code), caller));
        var destination = newDestination == null ? null : validator.normalizeDestination(newDestination);
        var ttl = validator.validateTtl(ttlSeconds);
        var now = clock.instant();
        return repository.update(code, current -> {
            var updated = destination == null ? current : current.withDestination(destination, now);
            return ttl.map(t -> updated.withExpiry(now.plus(t), now)).orElse(updated);
        }).orElseThrow(() -> new LinkNotFoundException(code));
    }

    public ShortUrl deactivate(String code, Caller caller) {
        requireOwner(findOrThrow(code), caller);
        var now = clock.instant();
        return repository.update(code, current -> current.deactivated(now))
                .orElseThrow(() -> new LinkNotFoundException(code));
    }

    public AnalyticsSummary analytics(String code, Caller caller) {
        requireOwner(findOrThrow(code), caller);
        return repository.analytics(code, TOP_REFERRERS);
    }

    private CreateResult replay(String scopedKey, String fingerprint) {
        var entry = idempotency.find(scopedKey).orElseThrow(IdempotencyInProgressException::new);
        if (!entry.payloadFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException();
        }
        if (entry.code() == null) {
            throw new IdempotencyInProgressException();
        }
        return new CreateResult(findOrThrow(entry.code()), false);
    }

    private ShortUrl createLink(CreateLinkCommand command) {
        var destination = validator.normalizeDestination(command.url());
        var alias = validator.validateAlias(command.alias());
        var ttl = validator.validateTtl(command.ttlSeconds());
        var now = clock.instant();
        Instant expiresAt = ttl.map(now::plus).orElse(null);
        Function<String, ShortUrl> build = code ->
                new ShortUrl(code, destination, now, expiresAt, alias.isPresent(), true, command.createdBy(), null);

        if (alias.isPresent()) {
            var link = build.apply(alias.get());
            if (!repository.saveIfAbsent(link)) {
                throw new AliasConflictException(alias.get());
            }
            return link;
        }
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            var link = build.apply(codeGenerator.next());
            if (repository.saveIfAbsent(link)) {
                return link;
            }
        }
        throw new IllegalStateException("unable to allocate a unique short code");
    }

    private ShortUrl findOrThrow(String code) {
        return repository.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    private static ShortUrl requireOwner(ShortUrl link, Caller caller) {
        if (!caller.canManage(link)) {
            throw new LinkAccessDeniedException(link.code());
        }
        return link;
    }

    private ShortUrl requireActive(ShortUrl link) {
        var code = link.code();
        if (!link.active()) {
            throw new LinkGoneException(code, "has been deactivated");
        }
        if (link.isExpired(clock.instant())) {
            throw new LinkGoneException(code, "has expired");
        }
        return link;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_HEADER_LENGTH ? value : value.substring(0, MAX_HEADER_LENGTH);
    }
}
