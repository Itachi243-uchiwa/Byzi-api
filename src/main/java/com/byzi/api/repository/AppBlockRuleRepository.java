package com.byzi.api.repository;

import com.byzi.api.domain.AppBlockRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppBlockRuleRepository extends JpaRepository<AppBlockRule, UUID> {
    Optional<AppBlockRule> findByIdAndUser_Id(UUID id, UUID userId);

    Page<AppBlockRule> findAllByUser_Id(UUID userId, Pageable pageable);
    long deleteByIdAndUser_Id(UUID id, UUID userId);

}

