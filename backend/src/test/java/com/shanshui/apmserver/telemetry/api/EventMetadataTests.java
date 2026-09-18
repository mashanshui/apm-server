package com.shanshui.apmserver.telemetry.api;

import com.shanshui.apmserver.crash.internal.domain.AppStartEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventMetadataTests {

    @Test
    void mapsEveryExistingCommonEventFieldWithoutLoss() {
        UUID appId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-03T01:02:03Z");
        Instant receivedAt = occurredAt.plusSeconds(2);
        EventMetadata metadata = new EventMetadata(appId, "com.example.app", "event-1", "app_start",
                occurredAt, receivedAt, 2, "session-1", "11111111-1111-4111-8111-111111111111", "device-1", "1.2.3", 123,
                "build-1", "prod", "stable", "16", "Pixel", "wifi",
                Map.of("duration", 12), Map.of("region", "cn"));
        AppStartEvent existing = new AppStartEvent(metadata);

        assertThat(existing.appId()).isEqualTo(appId);
        assertThat(existing.packageName()).isEqualTo("com.example.app");
        assertThat(existing.eventId()).isEqualTo("event-1");
        assertThat(existing.occurredAt()).isEqualTo(occurredAt);
        assertThat(existing.receivedAt()).isEqualTo(receivedAt);
        assertThat(metadata.measurements()).containsEntry("duration", 12);
        assertThat(metadata.attributes()).containsEntry("region", "cn");
    }

    @Test
    void appendResultRejectsNegativeCounts() {
        assertThatThrownBy(() -> new AppendResult(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
