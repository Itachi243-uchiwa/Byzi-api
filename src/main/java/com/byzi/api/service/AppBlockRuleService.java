package com.byzi.api.service;

import com.byzi.api.domain.AppBlockRule;
import com.byzi.api.domain.User;
import com.byzi.api.dto.blockrule.AppBlockRuleRequest;
import com.byzi.api.dto.blockrule.AppBlockRuleResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.mapper.AppBlockRuleMapper;
import com.byzi.api.repository.AppBlockRuleRepository;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.sync.ConflictResolutionStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppBlockRuleService {

    private final AppBlockRuleRepository appBlockRuleRepository;
    private final UserRepository userRepository;
    private final AppBlockRuleMapper mapper;
    private final ConflictResolutionStrategy conflictResolutionStrategy;


    @Transactional
    public AppBlockRuleResponse upsert(UUID id, UUID userId, AppBlockRuleRequest request) {
        var existing = appBlockRuleRepository.findByIdAndUser_Id(id, userId);

        var storedUpdatedAt = existing.map(AppBlockRule::getUpdatedAt).orElse(null);
        boolean shouldApply = conflictResolutionStrategy.shouldApplyIncoming(request.clientUpdatedAt(), storedUpdatedAt);

        if (!shouldApply) {
            return mapper.toResponse(existing.orElseThrow());
        }

        AppBlockRule entity = existing
                .map(current -> {
                    mapper.applyUpdate(current, request);
                    return current;
                })
                .orElseGet(() -> {
                    User owner = userRepository.getReferenceById(userId);
                    return mapper.toNewEntity(id, owner, request);
                });

        return mapper.toResponse(appBlockRuleRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public AppBlockRuleResponse get(UUID id, UUID userId) {
        return appBlockRuleRepository.findByIdAndUser_Id(id, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Regle de blocage introuvable"));
    }

    @Transactional(readOnly = true)
    public Page<AppBlockRuleResponse> list(UUID userId, Pageable pageable) {
        return appBlockRuleRepository.findAllByUser_Id(userId, pageable)
                .map(mapper::toResponse);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        long deleted = appBlockRuleRepository.deleteByIdAndUser_Id(id, userId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("Regle de blocage introuvable");
        }
    }
}