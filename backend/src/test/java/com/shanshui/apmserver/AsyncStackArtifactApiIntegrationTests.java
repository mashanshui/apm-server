package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.web.StackArtifactController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实 Processor 验证异步 ZIP 的鉴权、解析、归一化和幂等写入。 */
@SpringBootTest
@AutoConfigureMockMvc
class AsyncStackArtifactApiIntegrationTests extends AppIngestApiTestSupport {

    /** 含真实控制器和解析器的 HTTP 测试入口。 */
    @Autowired
    private MockMvc mockMvc;

    /** 同一合成异步 ZIP 首次接受，重复上传保持幂等。 */
    @Test
    void acceptsAndDeduplicatesAsyncMainThreadArtifact() throws Exception {
        resetAppCredential("com.example.app");
        // 测试输入没有真实设备数据，固定 eventId 用于验证同字节重试。
        byte[] artifact;
        try (var input = getClass().getResourceAsStream("/fixtures/rhea-jank-async-artifact.b64")) {
            assertNotNull(input);
            artifact = Base64.getMimeDecoder().decode(input.readAllBytes());
        }
        // 同一请求经过真实 processor 后分别触发接受和重复分支。
        for (String expectedStatus : new String[]{"accepted", "duplicate"}) {
            mockMvc.perform(post("/ingest/v1/stack-artifacts:parse")
                            .header("X-App-Key", appKey())
                            .contentType(StackArtifactController.ARTIFACT_MEDIA_TYPE)
                            .content(artifact))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(expectedStatus));
        }
    }
}
