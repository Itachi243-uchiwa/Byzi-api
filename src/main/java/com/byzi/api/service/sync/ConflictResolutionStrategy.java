package com.byzi.api.service.sync;

import java.time.Instant;

public interface ConflictResolutionStrategy {
    boolean shouldApplyIncoming(Instant incomingClientUpdatedAt, Instant storedUpdatedAt);
}
