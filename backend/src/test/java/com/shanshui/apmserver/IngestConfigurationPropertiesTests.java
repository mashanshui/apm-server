package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.crash.api.CrashIngestConfiguration;
import com.shanshui.apmserver.ingest.api.IngestConfiguration;
import com.shanshui.apmserver.jank.api.JankIngestConfiguration;
import com.shanshui.apmserver.jank.api.JankMetricsConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestConfigurationPropertiesTests {

    @Test
    void keepsExistingKeysWhileExposingModuleSpecificViews() {
        var source = new MapConfigurationPropertySource(Map.of(
                "apm.ingest.max-request-bytes", "4096",
                "apm.ingest.max-stack-frames", "80",
                "apm.ingest.max-jank-samples", "120",
                "apm.ingest.max-frame-histogram-buckets", "24"));
        IngestConfigurationProperties properties = new Binder(source)
                .bind("apm.ingest", Bindable.of(IngestConfigurationProperties.class))
                .orElseThrow(() -> new AssertionError("配置绑定失败"));

        IngestConfiguration ingest = properties;
        CrashIngestConfiguration crash = properties;
        JankIngestConfiguration jank = properties;
        JankMetricsConfiguration metrics = properties;
        assertEquals(4096, ingest.getMaxRequestBytes());
        assertEquals(80, crash.getMaxStackFrames());
        assertEquals(120, jank.getMaxJankSamples());
        assertEquals(24, metrics.getMaxFrameHistogramBuckets());
    }
}
