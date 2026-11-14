package com.shanshui.apmserver.symbol.internal.retrace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将受控 LLVM JSON 转换为不含编译机路径的物理帧结果。 */
public final class NativeSymbolizerJson {
    /** 单物理帧完整结果及内联项上限。 */
    private static final int MAX_FRAME_BYTES = 16 * 1024;
    private static final int MAX_INLINE_ITEMS = 32;
    /** 与服务端一致的 JSON 解析器。 */
    private final ObjectMapper mapper;

    /** 一项有序内联结果，行号和列号未知时为空。 */
    public record Inline(String function, String file, Integer line, Integer column) { }

    /** 一帧的完整结果；没有真实函数名时保留安全失败原因。 */
    public record Frame(List<Inline> inline, String reason) {
        /** 判断该物理帧是否取得真实函数。 */
        public boolean successful() { return reason == null; }
    }

    /** 注入项目使用的 JSON 解析器。 */
    public NativeSymbolizerJson(ObjectMapper mapper) { this.mapper = mapper; }

    /** 标准化完整无符号 64 位地址，不进行 PC 修正。 */
    public static String canonicalAddress(String address) {
        if (address == null || !address.matches("0[xX][0-9a-fA-F]{1,16}")) throw new IllegalArgumentException("非法 Native 地址");
        return "0x" + new BigInteger(address.substring(2), 16).toString(16);
    }

    /** 严格检查每个请求地址对应的单行 JSON，并保持输入顺序。 */
    public Map<String, Frame> parse(byte[] bytes, List<String> requested) {
        /** 规范地址去重后用于校验输出身份。 */
        var expected = new java.util.LinkedHashSet<String>();
        for (String address : requested) expected.add(canonicalAddress(address));
        var frames = new LinkedHashMap<String, Frame>();
        String output = new String(bytes, StandardCharsets.UTF_8);
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            if (!node.isObject() || !node.path("Address").isTextual() || !node.path("Symbol").isArray()) {
                throw new IllegalArgumentException("Native 工具输出格式无效");
            }
            String address = canonicalAddress(node.path("Address").asText());
            if (!expected.contains(address) || frames.containsKey(address)) throw new IllegalArgumentException("Native 工具输出地址不匹配");
            if (line.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES || node.path("Symbol").size() > MAX_INLINE_ITEMS) {
                frames.put(address, new Frame(List.of(), "native_output_limit"));
                continue;
            }
            var items = new ArrayList<Inline>();
            boolean hasFunction = false;
            for (JsonNode entry : node.path("Symbol")) {
                if (!entry.isObject()) throw new IllegalArgumentException("Native 内联项无效");
                String function = text(entry.path("FunctionName"));
                String file = fileName(text(entry.path("FileName")));
                if (function != null) hasFunction = true;
                items.add(new Inline(function, file, positive(entry.path("Line")), positive(entry.path("Column"))));
            }
            frames.put(address, hasFunction ? new Frame(List.copyOf(items), null) : new Frame(List.of(), "native_no_function"));
        }
        if (!frames.keySet().equals(expected)) throw new IllegalArgumentException("Native 工具输出不完整");
        /** 输出顺序不能依赖工具返回顺序。 */
        var ordered = new LinkedHashMap<String, Frame>();
        expected.forEach(address -> ordered.put(address, frames.get(address)));
        return java.util.Collections.unmodifiableMap(ordered);
    }

    /** 将未知标记或空文本归一为空。 */
    private static String text(JsonNode node) {
        if (!node.isTextual()) return null;
        String value = node.asText();
        return value.isBlank() || value.equals("??") ? null : value;
    }

    /** 仅保留文件名，避免输出 Unix 或 Windows 编译机目录。 */
    private static String fileName(String path) {
        if (path == null) return null;
        int position = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = path.substring(position + 1);
        return name.isBlank() ? null : name;
    }

    /** 零及无效数值代表位置未知。 */
    private static Integer positive(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0 ? node.intValue() : null;
    }
}
