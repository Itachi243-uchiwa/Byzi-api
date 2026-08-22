package com.buzi.api.sync;

import com.buzi.api.service.sync.LastWriteStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LastWriteWinsStrategyTest {

    private final LastWriteStrategy strategy = new LastWriteStrategy();

    @Test
    void appliesIncoming_whenNothingStoredYet() {
        assertThat(strategy.shouldApplyIncoming(Instant.now(), null)).isTrue();
    }

    @Test
    void appliesIncoming_whenClientDoesNotSendTimestamp() {
        assertThat(strategy.shouldApplyIncoming(null, Instant.now())).isTrue();
    }

    @Test
    void appliesIncoming_whenStrictlyNewerThanStored() {
        Instant stored = Instant.now();
        Instant incoming = stored.plus(1, ChronoUnit.SECONDS);

        assertThat(strategy.shouldApplyIncoming(incoming, stored)).isTrue();
    }

    @Test
    void rejectsIncoming_whenOlderThanStored() {
        Instant stored = Instant.now();
        Instant incoming = stored.minus(1, ChronoUnit.SECONDS);

        assertThat(strategy.shouldApplyIncoming(incoming, stored)).isFalse();
    }

    @Test
    void rejectsIncoming_whenExactlyEqualToStored() {
        Instant timestamp = Instant.now();

        assertThat(strategy.shouldApplyIncoming(timestamp, timestamp)).isFalse();
    }
}