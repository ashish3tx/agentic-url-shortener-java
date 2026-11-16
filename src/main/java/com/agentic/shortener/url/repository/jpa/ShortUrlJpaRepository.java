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

interface ShortUrlJpaRepository extends JpaRepository<ShortUrlEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ShortUrlEntity u where u.code = :code")
    Optional<ShortUrlEntity> findForUpdate(String code);
}
