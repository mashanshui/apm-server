package com.shanshui.apmserver.symbol.internal.archive;

import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 验证 ZIP 库包装格式异常时，真实磁盘故障仍保留独立的暂时错误类别。 */
class NativeArchiveChannelTests {
    /** 读取与索引阶段的底层故障不能进入 ZIP 内容异常分支。 */
    @Test
    void preservesStorageFailuresThroughZipIndexAndRead() throws IOException {
        /** 模拟文件系统故障，而非手动构造 ZIP 格式错误。 */
        var source = mock(SeekableByteChannel.class);
        var failure = new IOException("private path must not be exposed");
        var channel = new NativeArchiveChannel(source);
        when(source.read(any(ByteBuffer.class))).thenThrow(failure);
        assertSame(failure, assertThrows(SymbolStoreUnavailableException.class,
                () -> channel.read(ByteBuffer.allocate(4))).getCause());
        when(source.size()).thenThrow(failure);
        assertSame(failure, assertThrows(SymbolStoreUnavailableException.class,
                () -> ZipFile.builder().setSeekableByteChannel(channel).get()).getCause());
    }

    /** 通道关闭故障同样是存储错误，且包装器从不允许修改 ZIP。 */
    @Test
    void preservesCloseFailuresAndRejectsWrites() throws IOException {
        /** 关闭期间可观察的真实 I/O 原因。 */
        var source = mock(SeekableByteChannel.class);
        var failure = new IOException("close failed");
        var channel = new NativeArchiveChannel(source);
        doThrow(failure).when(source).close();
        assertSame(failure, assertThrows(SymbolStoreUnavailableException.class, channel::close).getCause());
        assertThrows(java.nio.channels.NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(java.nio.channels.NonWritableChannelException.class, () -> channel.truncate(0));
        verify(source, never()).write(any(ByteBuffer.class));
    }
}
