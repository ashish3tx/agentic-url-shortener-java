package com.agentic.shortener.url;

import com.agentic.shortener.url.error.InvalidLinkException;
import com.agentic.shortener.url.service.LinkValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkValidatorTest {

    private final LinkValidator validator = new LinkValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "https://sub.domain.example.org:8443/a/b",
            "https://93.184.216.34/",
            "https://[2606:2800:220:1:248:1893:25c8:1946]/"
    })
    void acceptsPublicHttpDestinations(String url) {
        assertThat(validator.normalizeDestination(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)", "data:text/html,hi", "file:///etc/passwd", "ftp://example.com",
            "http://127.0.0.1/", "http://10.1.2.3", "http://172.16.0.1", "http://192.168.1.4",
            "http://169.254.169.254/latest/meta-data", "http://0.0.0.0", "http://100.64.0.1",
            "http://[::1]/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/",
            "http://localhost:8080", "http://app.localhost", "http://db.internal", "http://printer.local",
            "http://metadata.google.internal", "http://2130706433/", "http://0x7f.0x0.0x0.0x1/", "http://017700000001/",
            "https://user:pass@example.com", "not a url", "https://"
    })
    void rejectsUnsafeOrMalformedDestinations(String url) {
        assertThatThrownBy(() -> validator.normalizeDestination(url))
                .isInstanceOf(InvalidLinkException.class)
                .extracting(e -> ((InvalidLinkException) e).field()).isEqualTo("url");
    }

    @Test
    void rejectsOverlongAndBlankUrls() {
        assertThatThrownBy(() -> validator.normalizeDestination(" ")).isInstanceOf(InvalidLinkException.class);
        assertThatThrownBy(() -> validator.normalizeDestination("https://example.com/" + "a".repeat(2100)))
                .hasMessageContaining("2048");
    }

    @Test
    void aliasRules() {
        assertThat(validator.validateAlias(null)).isEmpty();
        assertThat(validator.validateAlias("Campaign_2026-q1")).contains("Campaign_2026-q1");
        assertThatThrownBy(() -> validator.validateAlias("ab")).isInstanceOf(InvalidLinkException.class);
        assertThatThrownBy(() -> validator.validateAlias("has space")).isInstanceOf(InvalidLinkException.class);
        assertThatThrownBy(() -> validator.validateAlias("Actuator")).hasMessageContaining("reserved");
    }

    @Test
    void ttlBounds() {
        assertThat(validator.validateTtl(null)).isEmpty();
        assertThat(validator.validateTtl(60L)).isPresent();
        assertThatThrownBy(() -> validator.validateTtl(59L)).isInstanceOf(InvalidLinkException.class);
        assertThatThrownBy(() -> validator.validateTtl(LinkValidator.MAX_TTL_SECONDS + 1)).isInstanceOf(InvalidLinkException.class);
    }
}
