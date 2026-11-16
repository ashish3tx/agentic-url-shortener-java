package com.agentic.shortener.url.repository.jpa;

import com.agentic.shortener.url.domain.AnalyticsSummary;
import com.agentic.shortener.url.domain.ClickEvent;
import com.agentic.shortener.url.domain.ClickStats;
import com.agentic.shortener.url.domain.ShortUrl;
import com.agentic.shortener.url.repository.UrlRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

@Repository
class JpaUrlRepository implements UrlRepository {

    private final ShortUrlJpaRepository links;
    private final ClickEventJpaRepository clicks;

    JpaUrlRepository(ShortUrlJpaRepository links, ClickEventJpaRepository clicks) {
        this.links = links;
        this.clicks = clicks;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShortUrl> findByCode(String code) {
        return links.findById(code).map(ShortUrlEntity::toDomain);
    }

    // Deliberately not @Transactional: the insert runs in its own transaction so a primary-key
    // clash rolls back cleanly and surfaces here as a translated exception.
    @Override
    public boolean saveIfAbsent(ShortUrl link) {
        try {
            links.saveAndFlush(ShortUrlEntity.insert(link));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }

    @Override
    @Transactional
    public Optional<ShortUrl> update(String code, UnaryOperator<ShortUrl> change) {
        // SELECT ... FOR UPDATE serialises concurrent PATCH/DELETE on the same link.
        return links.findForUpdate(code).map(entity -> {
            var updated = change.apply(entity.toDomain());
            entity.apply(updated);
            return updated;
        });
    }

    @Override
    public void recordClick(ClickEvent event) {
        clicks.save(ClickEventEntity.from(event));
    }

    @Override
    @Transactional(readOnly = true)
    public AnalyticsSummary analytics(String code, int topReferrers) {
        Map<LocalDate, Long> byDay = new LinkedHashMap<>();
        clicks.clicksByDay(code).forEach(row -> byDay.put(row.day(), row.clicks()));
        Map<String, Long> referrers = new LinkedHashMap<>();
        clicks.topReferrers(code, PageRequest.of(0, topReferrers)).forEach(row -> referrers.put(row.referrer(), row.clicks()));
        return new AnalyticsSummary(code, clicks.countByCode(code), clicks.countDistinctVisitors(code),
                clicks.lastClickedAt(code).orElse(null), byDay, referrers);
    }

    @Override
    @Transactional
    public int purgeClicksBefore(Instant cutoff) {
        return clicks.deleteOlderThan(cutoff);
    }

    @Override
    public long linkCount() {
        return links.count();
    }
}
