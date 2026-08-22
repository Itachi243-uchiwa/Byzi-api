package com.byzi.api.service.sync;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class LastWriteStrategy implements ConflictResolutionStrategy{
    @Override
    public boolean shouldApplyIncoming(Instant incomingClientUpdatedAt, Instant storedUpdatedAt) {
        if (storedUpdatedAt == null) {
            return true;
        }
        if (incomingClientUpdatedAt == null) {
            return true;
        }
        return incomingClientUpdatedAt.isAfter(storedUpdatedAt);
    }
}
