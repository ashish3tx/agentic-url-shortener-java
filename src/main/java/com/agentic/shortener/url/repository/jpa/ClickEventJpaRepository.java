package com.agentic.shortener.url.repository.jpa;

import com.agentic.shortener.url.domain.ClickStats;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface ClickEventJpaRepository extends JpaRepository<ClickEventEntity, Long> {

    long countByCode(String code);

    @Query("select count(distinct c.visitorHash) from ClickEventEntity c where c.code = :code")
    long countDistinctVisitors(String code);

    @Query("select max(c.clickedAt) from ClickEventEntity c where c.code = :code")
    Optional<Instant> lastClickedAt(String code);

    @Query("""
            select new com.agentic.shortener.url.domain.ClickStats$DayCount(c.clickDay, count(c))
            from ClickEventEntity c where c.code = :code group by c.clickDay order by c.clickDay""")
    List<ClickStats.DayCount> clicksByDay(String code);

    @Query("""
            select new com.agentic.shortener.url.domain.ClickStats$ReferrerCount(c.referrer, count(c))
            from ClickEventEntity c where c.code = :code group by c.referrer order by count(c) desc""")
    List<ClickStats.ReferrerCount> topReferrers(String code, Pageable page);

    @Modifying
    @Query("delete from ClickEventEntity c where c.clickedAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}
