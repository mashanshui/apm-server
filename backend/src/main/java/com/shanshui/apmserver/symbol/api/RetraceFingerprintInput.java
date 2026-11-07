package com.shanshui.apmserver.symbol.api;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** 完整输出的归组投影：保留异常链、内联顺序和每组全部候选，不持久化第二份堆栈。 */
public record RetraceFingerprintInput(
        /** 按原因链顺序保留异常头，消息由 Crash 指纹算法规范化。 */ List<String> exceptions,
        /** 顺序帧组；每组候选按身份排序，候选顺序变化不影响指纹。 */ List<List<String>> frameGroups) {
    /** Java 帧身份，不依赖文件名或行号。 */
    private static final Pattern FRAME = Pattern.compile("^\\s*(?:<OR>\\s*)?at\\s+([^\\s(]+)\\([^)]*\\)\\s*$");

    /** 从 R8 标准文本构造完整投影；没有候选被任意选为唯一根因。 */
    public static RetraceFingerprintInput fromText(String text) {
        // 临时集合仅在本次计算期间保留。
        List<String> headers = new ArrayList<>();
        List<TreeSet<String>> groups = new ArrayList<>();
        for (String line : text.split("\\R")) {
            // R8 使用 <OR> 标识同一输入帧的其他候选；普通连续帧包含内联展开顺序。
            var match = FRAME.matcher(line);
            if (match.matches()) {
                if (!line.stripLeading().startsWith("<OR>") || groups.isEmpty()) groups.add(new TreeSet<>());
                groups.getLast().add(match.group(1));
            } else if (!line.isBlank() && !line.stripLeading().startsWith("...")) {
                headers.add(line.strip().replaceFirst("^Caused by:\\s*", ""));
            }
        }
        return new RetraceFingerprintInput(List.copyOf(headers), groups.stream().map(List::copyOf).toList());
    }
}
