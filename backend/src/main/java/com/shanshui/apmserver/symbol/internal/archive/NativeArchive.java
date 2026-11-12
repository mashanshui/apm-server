package com.shanshui.apmserver.symbol.internal.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 一次归档校验的临时文件；调用方完成后必须释放。 */
public final class NativeArchive implements AutoCloseable {
    /** 每个 SO 的原始条目名称和受控临时文件。 */
    public record Entry(
            /** 仅作受限显示，不用作文件路径。 */ String name,
            /** 服务端生成名称的临时文件。 */ Path path) {
    }

    /** 仅属于本次校验的暂存目录。 */
    private final Path directory;
    /** 经过安全检查的 SO 条目。 */
    private final List<Entry> entries;

    /** 记录成功校验后的临时目录与条目。 */
    NativeArchive(Path directory, List<Entry> entries) {
        this.directory = directory;
        this.entries = List.copyOf(entries);
    }

    /** 返回不可变的候选集合。 */
    public List<Entry> entries() {
        return entries;
    }

    /** 释放全部候选文件和暂存目录。 */
    @Override
    public void close() throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
