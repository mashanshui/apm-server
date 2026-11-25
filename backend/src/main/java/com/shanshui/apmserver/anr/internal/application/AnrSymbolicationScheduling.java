package com.shanshui.apmserver.anr.internal.application;

import com.shanshui.apmserver.anr.internal.persistence.AnrSymbolicationPublications;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** ANR 后台还原使用独立的单线程调度器。 */
@Configuration
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class AnrSymbolicationScheduling {
    /** 有界后台处理器。 */
    private final AnrSymbolicationProcessor processor;
    /** 到期发布记录清理器。 */
    private final AnrSymbolicationPublications publications;

    /** 绑定处理器与清理器。 */
    public AnrSymbolicationScheduling(AnrSymbolicationProcessor processor, AnrSymbolicationPublications publications) {
        this.processor = processor;
        this.publications = publications;
    }

    /** 单线程执行后台还原，不阻塞接收和查询。 */
    @Bean(name = "anrSymbolicationScheduler", defaultCandidate = false)
    public static ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("anr-symbolication-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    /** 固定延迟处理唤醒、未确认发布和有限候选页。 */
    @Scheduled(scheduler = "anrSymbolicationScheduler", fixedDelayString = "${apm.anr.background.interval-ms:1000}",
            initialDelayString = "${apm.anr.background.initial-delay-ms:10000}")
    public void process() {
        processor.runOnce();
    }

    /** 定期有限清理到期的发布记录。 */
    @Scheduled(scheduler = "anrSymbolicationScheduler", fixedDelayString = "${apm.anr.background.cleanup-interval-ms:3600000}",
            initialDelayString = "${apm.anr.background.cleanup-initial-delay-ms:3600000}")
    public void cleanup() {
        publications.cleanupExpired();
    }
}
