package com.shanshui.apmserver.symbol.internal.archive;

import java.util.zip.ZipException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 在建立归档索引前限制 ZIP 尾部和中央目录，拒绝 ZIP64 与多卷。 */
final class ZipDirectoryPreflight {
    /** EOCD 固定长度及允许的 ZIP 注释最大长度。 */
    private static final int EOCD_LENGTH = 22;
    private static final int MAX_COMMENT = 65535;
    /** 受限的压缩体及中央目录预算。 */
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_DIRECTORY_BYTES = 1024L * 1024;
    /** 包括目录和辅助文件在内的最大条目数。 */
    private static final int MAX_ENTRIES = 256;

    /** 检查尾部声明并返回准确的中央目录条目数量。 */
    Directory inspect(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long length = channel.size();
            if (length < EOCD_LENGTH || length > MAX_ARCHIVE_BYTES) throw invalid();
            int tailSize = (int) Math.min(length, EOCD_LENGTH + MAX_COMMENT);
            long tailOffset = length - tailSize;
            ByteBuffer tail = read(channel, tailOffset, tailSize);
            int end = -1;
            for (int index = tailSize - EOCD_LENGTH; index >= 0; index--) {
                if (tail.getInt(index) == 0x06054b50 && index + EOCD_LENGTH
                        + Short.toUnsignedInt(tail.getShort(index + 20)) == tailSize) {
                    end = index;
                    break;
                }
            }
            if (end < 0 || tail.getShort(end + 4) != 0 || tail.getShort(end + 6) != 0) throw invalid();
            int diskEntries = Short.toUnsignedInt(tail.getShort(end + 8));
            int entries = Short.toUnsignedInt(tail.getShort(end + 10));
            long directorySize = Integer.toUnsignedLong(tail.getInt(end + 12));
            long directoryOffset = Integer.toUnsignedLong(tail.getInt(end + 16));
            long eocdOffset = tailOffset + end;
            if (entries != diskEntries || entries == 0xffff || entries > MAX_ENTRIES
                    || directorySize == 0xffffffffL || directoryOffset == 0xffffffffL
                    || directorySize > MAX_DIRECTORY_BYTES || directoryOffset > eocdOffset
                    || directorySize != eocdOffset - directoryOffset) throw invalid();
            return new Directory(entries, directoryOffset);
        }
    }

    /** 经预算校验的目录入口和条目数量。 */
    record Directory(/** 条目数。 */ int entries, /** 中央目录起点。 */ long offset) {
    }

    /** 使用指定位置读取有界尾部，文件变短时拒绝。 */
    private ByteBuffer read(FileChannel channel, long offset, int size) throws IOException {
        ByteBuffer result = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (result.hasRemaining()) {
            int read = channel.read(result, offset + result.position());
            if (read < 0) throw new ZipException("ZIP 文件已截断");
            if (read == 0) throw new IOException("ZIP 读取未取得数据");
        }
        return result.flip();
    }

    /** 不回显归档名称和内容。 */
    private ZipException invalid() {
        return new ZipException("ZIP 中央目录或资源预算无效");
    }
}
