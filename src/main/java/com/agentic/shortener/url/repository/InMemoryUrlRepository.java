package com.agentic.shortener.url.repository;

import com.agentic.shortener.url.domain.AnalyticsSummary;
import com.agentic.shortener.url.domain.ClickEvent;
import com.agentic.shortener.url.domain.ShortUrl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Thread-safe in-memory adapter. Not a Spring bean: used for sandboxes and tests. */
public class InMemoryUrlRepository implements UrlRepository {

    private final Map<String, ShortUrl> links = new ConcurrentHashMap<>();
    private final Map<String, Queue<ClickEvent>> clicks = new ConcurrentHashMap<>();

    @Override
    public Optional<ShortUrl> findByCode(String code) {
        return Optional.ofNullable(links.get(code));
    }

    @Override
    public boolean saveIfAbsent(ShortUrl link) {
        return links.putIfAbsent(link.code(), link) == null;
    }

    @Override
    public Optional<ShortUrl> update(String code, UnaryOperator<ShortUrl> change) {
        return Optional.ofNullable(links.computeIfPresent(code, (key, current) -> change.apply(current)));
    }

    @Override
    public void recordClick(ClickEvent event) {
        clicks.computeIfAbsent(event.code(), key -> new ConcurrentLinkedQueue<>()).add(event);
    }

    @Override
    public AnalyticsSummary analytics(String code, int topReferrers) {
        var events = clicksFor(code);
        Map<LocalDate, Long> byDay = events.stream().collect(Collectors.groupingBy(
                c -> LocalDate.ofInstant(c.at(), ZoneOffset.UTC), TreeMap::new, Collectors.counting()));
        Map<String, Long> referrers = events.stream()
                .collect(Collectors.groupingBy(ClickEvent::referrer, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topReferrers)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        var lastClick = events.stream().map(ClickEvent::at).max(Comparator.naturalOrder()).orElse(null);
        long unique = events.stream().map(ClickEvent::visitorHash).distinct().count();
        return new AnalyticsSummary(code, events.size(), unique, lastClick, byDay, referrers);
    }

    public List<ClickEvent> clicksFor(String code) {
        return List.copyOf(clicks.getOrDefault(code, new ConcurrentLinkedQueue<>()));
    }

    @Override
    public int purgeClicksBefore(Instant cutoff) {
        int removed = 0;
        for (var queue : clicks.values()) {
            int before = queue.size();
            queue.removeIf(event -> event.at().isBefore(cutoff));
            removed += before - queue.size();
        }
        return removed;
    }

    @Override
    public long linkCount() {
        return links.size();
    }
}
