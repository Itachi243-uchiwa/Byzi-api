package com.byzi.api.service.admin;

import com.byzi.api.domain.AdminAuditLog;
import com.byzi.api.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Journal d'audit des actions d'administration (story 09.7).
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    public static final String ACTION_EXTEND_TRIAL = "EXTEND_TRIAL";
    public static final String ACTION_MARK_REFUNDED = "MARK_REFUNDED";
    public static final String ACTION_DELETE_ACCOUNT = "DELETE_ACCOUNT";

    private final AdminAuditLogRepository auditLogRepository;

    /**
     * Enregistre dans une transaction SEPAREE (REQUIRES_NEW) de l'action auditee.
     * <p>
     * C'est le point important : si l'ecriture d'audit partageait la transaction de l'action,
     * un rollback ulterieur effacerait la trace en meme temps que l'effet. Or on veut
     * precisement pouvoir constater qu'une suppression de compte a ete tentee, y compris
     * lorsqu'elle a echoue.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID adminId, String adminLabel, String action, UUID targetUserId, String details) {
        auditLogRepository.save(AdminAuditLog.builder()
                .id(UUID.randomUUID())
                .adminId(adminId)
                .adminLabel(adminLabel)
                .action(action)
                .targetUserId(targetUserId)
                .details(details)
                .occurredAt(Instant.now())
                .build());
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> list(Pageable pageable) {
        return auditLogRepository.findAllByOrderByOccurredAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public List<AdminAuditLog> forUser(UUID userId) {
        return auditLogRepository.findAllByTargetUserIdOrderByOccurredAtDesc(userId);
    }
}
