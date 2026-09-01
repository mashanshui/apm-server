package com.shanshui.apmserver;

import com.shanshui.apmserver.config.StackParserProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StackParserPropertiesTests {

    @Test
    void bindsArtifactLimitConcurrencyAndMappingRoot() {
        var source = new MapConfigurationPropertySource(Map.of(
                "apm.stack-parser.max-artifact-bytes", "4096",
                "apm.stack-parser.max-concurrent-parses", "3",
                "apm.stack-parser.mapping-root", "C:/apm/mappings"));

        StackParserProperties properties = new Binder(source)
                .bind("apm.stack-parser", Bindable.of(StackParserProperties.class))
                .orElseThrow(() -> new AssertionError("配置绑定失败"));

        assertEquals(4096L, properties.getMaxArtifactBytes());
        assertEquals(3, properties.getMaxConcurrentParses());
        assertEquals("C:/apm/mappings", properties.getMappingRoot());
    }

    @Test
    void keepsSafeDefaults() {
        StackParserProperties properties = new StackParserProperties();

        assertEquals(64L * 1024 * 1024, properties.getMaxArtifactBytes());
        assertEquals(2, properties.getMaxConcurrentParses());
        assertEquals("", properties.getMappingRoot());
    }
}
