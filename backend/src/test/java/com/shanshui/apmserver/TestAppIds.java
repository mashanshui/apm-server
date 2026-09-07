package com.shanshui.apmserver;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 测试用稳定应用 UUID，避免测试把字符串测试标识当作 appId。 */
public final class TestAppIds {

    private TestAppIds() {
    }

    public static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("test-app:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
