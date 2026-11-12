package com.shanshui.apmserver.symbol.internal.elf;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 只读、有界地访问 ELF 文件，所有读取先核对文件范围。 */
final class ElfFileReader implements AutoCloseable {
    /** 当前输入文件的只读通道。 */
    private final FileChannel channel;
    /** 打开时固定的文件长度。 */
    private final long length;
    /** 上传流程共享的单调时钟截止时间。 */
    private final long deadlineNanos;

    /** 打开只读输入，后续不执行或加载该文件。 */
    ElfFileReader(Path path, long deadlineNanos) throws IOException {
        this.deadlineNanos = deadlineNanos;
        checkDeadline();
        channel = FileChannel.open(path, StandardOpenOption.READ);
        length = channel.size();
    }

    /** 读取固定长度的数据，拒绝负数、溢出、截断和越界。 */
    ByteBuffer read(long offset, int size) throws IOException {
        checkDeadline();
        checkRange(offset, size);
        ByteBuffer result = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (result.hasRemaining()) {
            checkDeadline();
            int read = channel.read(result, offset + result.position());
            if (read < 0) throw new EOFException("ELF 文件读取时被截断");
            if (read == 0) throw new IOException("ELF 文件读取未取得数据");
        }
        return result.flip();
    }

    /** 不让符号表遍历在超时或线程中断后继续占用上传许可。 */
    void checkDeadline() throws IOException {
        if (Thread.currentThread().isInterrupted()
                || (deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0)) {
            throw new InterruptedIOException("ELF 检查已超时或中断");
        }
    }

    /** 在任何加法运算前检查区间不超出文件。 */
    void checkRange(long offset, long size) throws IOException {
        if (offset < 0 || size < 0 || offset > length || size > length - offset) {
            throw new ElfValidationException("INVALID_SO");
        }
    }

    /** 释放文件通道。 */
    @Override
    public void close() throws IOException {
        channel.close();
    }
}
