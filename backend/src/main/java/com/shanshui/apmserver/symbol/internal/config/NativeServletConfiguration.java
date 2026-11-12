package com.shanshui.apmserver.symbol.internal.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 上传流的单次网络阻塞由真实 Tomcat Connector 限制。 */
@Configuration
public class NativeServletConfiguration {
    /** 不依赖未受 Spring Boot 绑定的上传超时属性，直接配置容器协议处理器。 */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> nativeReceiveTimeouts() {
        return factory -> {
            // 提前拒绝或截止后不再吞读剩余 SO 正文，避免容器在许可释放后继续占用上传线程。
            factory.addEngineValves(new org.apache.catalina.valves.ValveBase(true) {
                /** 业务响应完成后、Connector 结束请求前关闭剩余正文吞读。 */
                @Override
                public void invoke(org.apache.catalina.connector.Request request, org.apache.catalina.connector.Response response)
                        throws java.io.IOException, jakarta.servlet.ServletException {
                    try { getNext().invoke(request, response); }
                    finally {
                        if ("POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/symbols/native-batches")
                                && response.getStatus() >= 400) {
                            request.getCoyoteRequest().action(org.apache.coyote.ActionCode.DISABLE_SWALLOW_INPUT, null);
                        }
                    }
                }
            });
            factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
                protocol.setDisableUploadTimeout(false);
                protocol.setConnectionUploadTimeout(60_000);
                org.slf4j.LoggerFactory.getLogger(NativeServletConfiguration.class).info("Servlet 上传读取超时已配置为 60 秒");
            }
            });
        };
    }
}
