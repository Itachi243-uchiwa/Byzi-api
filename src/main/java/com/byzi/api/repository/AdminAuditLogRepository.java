package com.byzi.api.repository;

import com.byzi.api.domain.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

    Page<AdminAuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    List<AdminAuditLog> findAllByTargetUserIdOrderByOccurredAtDesc(UUID targetUserId);
}
