package com.shanshui.apmserver.memory.internal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用内存报告附件的低频保留策略任务。 */
@Configuration
@EnableScheduling
public class MemoryLeakSchedulingConfiguration {
}
