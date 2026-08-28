package com.byzi.api.mapper;

import com.byzi.api.domain.AppBlockRule;
import com.byzi.api.domain.RuleKind;
import com.byzi.api.domain.User;
import com.byzi.api.dto.blockrule.AppBlockRuleRequest;
import com.byzi.api.dto.blockrule.AppBlockRuleResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AppBlockRuleMapper {

    public AppBlockRule toNewEntity(UUID id, User owner, AppBlockRuleRequest request) {
        return AppBlockRule.builder()
                .id(id)
                .user(owner)
                .selectionData(request.selectionData())
                .name(nameOrEmpty(request.name()))
                .kind(kindOrDefault(request.kind()))
                .dailyLimitMinutes(request.dailyLimitMinutes())
                .scheduleStart(request.scheduleStart())
                .scheduleEnd(request.scheduleEnd())
                .scheduleDays(request.scheduleDays())
                .isActive(request.active())
                .build();
    }

    public void applyUpdate(AppBlockRule entity, AppBlockRuleRequest request) {
        entity.setSelectionData(request.selectionData());
        entity.setName(nameOrEmpty(request.name()));
        entity.setKind(kindOrDefault(request.kind()));
        entity.setDailyLimitMinutes(request.dailyLimitMinutes());
        entity.setScheduleStart(request.scheduleStart());
        entity.setScheduleEnd(request.scheduleEnd());
        entity.setScheduleDays(request.scheduleDays());
        entity.setActive(request.active());
    }

    public AppBlockRuleResponse toResponse(AppBlockRule entity) {
        return new AppBlockRuleResponse(
                entity.getId(),
                entity.getSelectionData(),
                entity.getName(),
                entity.getKind(),
                entity.getDailyLimitMinutes(),
                entity.getScheduleStart(),
                entity.getScheduleEnd(),
                entity.getScheduleDays(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }

    private String nameOrEmpty(String name) {
        return name == null ? "" : name;
    }

    private RuleKind kindOrDefault(RuleKind kind) {
        return kind == null ? RuleKind.FOCUS : kind;
    }
}
