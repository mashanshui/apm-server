package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 后台构建使用独立单线程调度，固定延迟不积压内存任务。 */
@Configuration
public class CrashBackgroundScheduling {
    /** 有界构建执行器。 */
    private final CrashBackgroundProcessor processor;
    /** 持久化轮转对账。 */
    private final CrashSymbolicationReconciler reconciler;
    /** 到期轻量记录和在途载荷清理。 */
    private final CrashSymbolicationPublications publications;
    /** 到期构建工作清理。 */
    private final CrashSymbolicationJobs jobs;
    /** 关闭时不再开始后台工作，已有 pending 仍保留。 */
    private final boolean enabled;

    /** 注入执行器与明确部署开关。 */
    public CrashBackgroundScheduling(CrashBackgroundProcessor processor, CrashSymbolicationReconciler reconciler,
            CrashSymbolicationPublications publications, CrashSymbolicationJobs jobs,
            @Value("${apm.crash.background.enabled:true}") boolean enabled) {
        this.processor = processor;
        this.reconciler = reconciler;
        this.publications = publications;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    /** 固定一个构建线程；没有每事件队列，也不共享单事件分析维护线程。 */
    @Bean(name = "crashSymbolicationScheduler", defaultCandidate = false)
    public static ThreadPoolTaskScheduler scheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("crash-symbolication-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    /** 请求路径只登记，批次执行总在独立线程中开始。 */
    @Scheduled(scheduler = "crashSymbolicationScheduler", fixedDelayString = "${apm.crash.background.interval-ms:1000}",
            initialDelayString = "${apm.crash.background.initial-delay-ms:5000}")
    public void process() { if (enabled) processor.runOnce(); }

    /** 轮转和清理均有数据库返回数量上限，失败交给下一次持久化重试。 */
    @Scheduled(fixedDelayString = "${apm.crash.background.reconcile-interval-ms:30000}",
            initialDelayString = "${apm.crash.background.reconcile-initial-delay-ms:10000}")
    public void reconcile() {
        if (!enabled) return;
        reconciler.reconcilePage();
        publications.cleanupExpired();
        jobs.cleanupExpired();
    }
}
