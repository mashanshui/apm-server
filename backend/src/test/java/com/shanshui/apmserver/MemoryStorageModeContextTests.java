package com.shanshui.apmserver;

import com.shanshui.apmserver.memory.internal.persistence.ClickHouseMemoryMetricsRepository;
import com.shanshui.apmserver.memory.internal.persistence.InMemoryMemoryMetricsRepository;
import com.shanshui.apmserver.memory.internal.port.MemoryEventRepository;
import com.shanshui.apmserver.memory.internal.port.MemoryMetricsRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证内存域在两种存储模式下只装配一个写入/查询适配器。 */
class MemoryStorageModeContextTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TestDependencies.class, InMemoryMemoryMetricsRepository.class,
                    ClickHouseMemoryMetricsRepository.class);

    @Test
    void memoryModeUsesOnlyInMemoryAdapter() {
        context.withPropertyValues("apm.storage.mode=memory", "apm.storage.in-memory-available=true")
                .run(application -> {
                    assertThat(application.getBeansOfType(MemoryEventRepository.class)).hasSize(1)
                            .containsValue(application.getBean(InMemoryMemoryMetricsRepository.class));
                    assertThat(application.getBeansOfType(MemoryMetricsRepository.class)).hasSize(1)
                            .containsValue(application.getBean(InMemoryMemoryMetricsRepository.class));
                    assertThat(application.getBeansOfType(ClickHouseMemoryMetricsRepository.class)).isEmpty();
                });
    }

    @Test
    void clickHouseModeUsesOnlyClickHouseAdapter() {
        context.withPropertyValues("apm.storage.mode=clickhouse")
                .run(application -> {
                    assertThat(application.getBeansOfType(MemoryEventRepository.class)).hasSize(1)
                            .containsValue(application.getBean(ClickHouseMemoryMetricsRepository.class));
                    assertThat(application.getBeansOfType(MemoryMetricsRepository.class)).hasSize(1)
                            .containsValue(application.getBean(ClickHouseMemoryMetricsRepository.class));
                    assertThat(application.getBeansOfType(InMemoryMemoryMetricsRepository.class)).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        StorageProperties storageProperties() {
            return new StorageProperties();
        }

        @Bean
        ClickHouseProperties clickHouseProperties() {
            return new ClickHouseProperties();
        }

        @Bean
        ClickHouseHttpClient clickHouseHttpClient(ClickHouseProperties properties) {
            return new ClickHouseHttpClient(properties);
        }
    }
}
