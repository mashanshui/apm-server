package com.shanshui.apmserver.symbol.internal.archive;

import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/** 在 ZIP 库包装异常前保留真实磁盘故障身份，防止将读盘失败解释为格式错误。 */
final class NativeArchiveChannel implements SeekableByteChannel {
    /** 已由调用方打开的只读文件通道。 */
    private final SeekableByteChannel source;

    /** 包装同一份有界暂存 ZIP，不复制文件或加载整个压缩体。 */
    NativeArchiveChannel(SeekableByteChannel source) { this.source = source; }

    /** 底层读取失败直接作为暂时存储故障传播。 */
    @Override public int read(ByteBuffer target) {
        try { return source.read(target); }
        catch (IOException failure) { throw new SymbolStoreUnavailableException(failure); }
    }

    /** 只读校验不允许写入归档。 */
    @Override public int write(ByteBuffer input) throws IOException { throw new NonWritableChannelException(); }

    /** 获取当前读取位置，保留底层存储错误类别。 */
    @Override public long position() {
        try { return source.position(); }
        catch (IOException failure) { throw new SymbolStoreUnavailableException(failure); }
    }

    /** ZIP 库的有界随机读取继续复用当前只读通道。 */
    @Override public SeekableByteChannel position(long position) {
        try { source.position(position); return this; }
        catch (IOException failure) { throw new SymbolStoreUnavailableException(failure); }
    }

    /** 查询实际文件大小，不能将读取失败冒充空归档。 */
    @Override public long size() {
        try { return source.size(); }
        catch (IOException failure) { throw new SymbolStoreUnavailableException(failure); }
    }

    /** 禁止解析器截断归档。 */
    @Override public SeekableByteChannel truncate(long size) throws IOException { throw new NonWritableChannelException(); }

    /** 返回底层通道是否仍打开。 */
    @Override public boolean isOpen() { return source.isOpen(); }

    /** 关闭异常属于存储故障，不改变为文件内容错误。 */
    @Override public void close() {
        try { source.close(); }
        catch (IOException failure) { throw new SymbolStoreUnavailableException(failure); }
    }
}
