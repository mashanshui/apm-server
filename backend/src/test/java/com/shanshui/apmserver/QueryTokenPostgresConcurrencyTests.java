package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.api.QueryTokenLimitException;
import com.shanshui.apmserver.identity.internal.application.AppManagementService;
import com.shanshui.apmserver.identity.internal.application.AppQueryTokenService;
import com.shanshui.apmserver.identity.internal.domain.AppCreateRequest;
import com.shanshui.apmserver.identity.internal.persistence.AppQueryTokenRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真实 PostgreSQL 行锁验证两个同时创建请求不能越过单应用上限。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "apm.query-token.max-active=1"})
class QueryTokenPostgresConcurrencyTests {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 将独立测试容器配置给 Spring 与 Flyway。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private AppUserRepository users;
    @Autowired private AppManagementService apps;
    @Autowired private AppQueryTokenService tokens;
    @Autowired private AppQueryTokenRepository tokenRows;

    /** 两个事务争抢同一应用行锁，最终只有一条有效 Token。 */
    @Test
    void serializesConcurrentCreationAtActiveLimit() throws Exception {
        UUID userId = users.findByEmailNormalized("test@example.com").orElseThrow().getId();
        UUID appId = apps.create(userId, new AppCreateRequest("com.example.token.concurrent")).appId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> createAfterBarrier(userId, appId, ready, release));
            Future<Boolean> second = executor.submit(() -> createAfterBarrier(userId, appId, ready, release));
            ready.await();
            release.countDown();
            int succeeded = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, succeeded);
            assertEquals(1, tokenRows.countByAppIdAndRevokedAtIsNullAndExpiresAtAfter(appId,
                    java.time.Instant.now()));
        }
    }

    /** 等待并发屏障后创建，限额错误是预期的另一个结果。 */
    private boolean createAfterBarrier(UUID userId, UUID appId, CountDownLatch ready, CountDownLatch release)
            throws InterruptedException {
        ready.countDown();
        release.await();
        try {
            tokens.create(userId, appId, "parallel", 30);
            return true;
        } catch (QueryTokenLimitException ex) {
            return false;
        }
    }
}
