package com.shanshui.apmserver.symbol.internal.retrace;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 LLVM 输出与原始物理地址的对应关系和安全位置。 */
class NativeSymbolizerJsonTests {
    /** 与业务一致的解析器。 */
    private final NativeSymbolizerJson parser = new NativeSymbolizerJson(new ObjectMapper());

    /** 内联链保持顺序、C++ 名称不变、行列零为未知且目录被移除。 */
    @Test
    void preservesInlineOrderAndRemovesSourceDirectories() {
        String json = """
                {"Address":"0xffffffffffffffff","Symbol":[{"FunctionName":"ns::Inner(int)","FileName":"/private/build/inner.cc","Line":0,"Column":0},{"FunctionName":"Outer()","FileName":"C:\\\\build\\\\outer.cc","Line":42,"Column":8}]}
                """;
        var frame = parser.parse(json.getBytes(StandardCharsets.UTF_8), List.of("0XFFFFFFFFFFFFFFFF")).get("0xffffffffffffffff");
        assertTrue(frame.successful());
        assertEquals(2, frame.inline().size());
        assertEquals("ns::Inner(int)", frame.inline().get(0).function());
        assertEquals("inner.cc", frame.inline().get(0).file());
        assertNull(frame.inline().get(0).line());
        assertNull(frame.inline().get(0).column());
        assertEquals("Outer()", frame.inline().get(1).function());
        assertEquals("outer.cc", frame.inline().get(1).file());
        assertEquals(42, frame.inline().get(1).line());
        assertEquals(8, frame.inline().get(1).column());
    }

    /** 无函数名不能因为存在行号或采集名称而冒充成功。 */
    @Test
    void treatsUnknownFunctionAsFailure() {
        String json = "{\"Address\":\"0x001\",\"Symbol\":[{\"FunctionName\":\"??\",\"FileName\":\"??\",\"Line\":4}]}";
        var result = parser.parse(json.getBytes(StandardCharsets.UTF_8), List.of("0x1", "0x0001"));
        assertEquals(1, result.size());
        assertEquals("native_no_function", result.get("0x1").reason());
        assertTrue(result.get("0x1").inline().isEmpty());
    }

    /** 不接受额外、重复、缺失或溢出的地址。 */
    @Test
    void rejectsMismatchedOrInvalidAddresses() {
        String row = "{\"Address\":\"0x2\",\"Symbol\":[]}";
        assertThrows(IllegalArgumentException.class, () -> parser.parse(row.getBytes(StandardCharsets.UTF_8), List.of("0x1")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new byte[0], List.of("0x1")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse((row + "\n" + row).getBytes(StandardCharsets.UTF_8), List.of("0x2")));
        assertThrows(IllegalArgumentException.class, () -> NativeSymbolizerJson.canonicalAddress("0x10000000000000000"));
    }

    /** 不截断超预算的内联链后伪装成完整成功。 */
    @Test
    void rejectsFrameExceedingInlineBudget() {
        String item = "{\"FunctionName\":\"function\",\"Line\":1}";
        String row = "{\"Address\":\"0x1\",\"Symbol\":[" + String.join(",", java.util.Collections.nCopies(33, item)) + "]}";
        assertEquals("native_output_limit", parser.parse(row.getBytes(StandardCharsets.UTF_8), List.of("0x1")).get("0x1").reason());
    }
}
