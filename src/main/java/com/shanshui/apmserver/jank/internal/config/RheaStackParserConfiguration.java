package com.shanshui.apmserver.jank.internal.config;

import com.bytedance.rheatrace.stack.StackAnalyzer;
import com.bytedance.rheatrace.stack.StackParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RheaStackParserConfiguration {

    @Bean
    public StackParser stackParser() {
        return new StackAnalyzer();
    }
}
