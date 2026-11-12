package com.shanshui.apmserver.symbol.internal.archive;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/** 严格消费完整的原始 DEFLATE 数据，拒绝截断、附加垃圾和伪造压缩长度。 */
final class StrictDeflateInputStream extends InputStream {
    /** 中央目录对应的有限压缩输入。 */
    private final InputStream source;
    /** 解压器只接受 ZIP 的裸 DEFLATE 格式。 */
    private final Inflater inflater = new Inflater(true);
    /** 原始读取缓冲区。 */
    private final byte[] compressed = new byte[8192];
    /** 中央目录声明的压缩长度。 */
    private final long expectedSize;
    /** 已检查完整 DEFLATE 流。 */
    private boolean finished;

    /** 包装有限原始流及其声明大小。 */
    StrictDeflateInputStream(InputStream source, long expectedSize) {
        this.source = source;
        this.expectedSize = expectedSize;
    }

    /** 实现单字节读取。 */
    @Override
    public int read() throws IOException {
        byte[] value = new byte[1];
        return read(value, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(value[0]);
    }

    /** 解压时验证全部输入被 DEFLATE 流消费。 */
    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        if (finished) return -1;
        try {
            while (true) {
                int count = inflater.inflate(target, offset, length);
                if (count > 0) return count;
                if (inflater.finished()) {
                    if (inflater.getBytesRead() != expectedSize || source.read() != -1) {
                        throw new ZipException("ZIP 压缩流长度不一致");
                    }
                    finished = true;
                    return -1;
                }
                if (inflater.needsDictionary()) throw new ZipException("ZIP 压缩流需要字典");
                if (inflater.needsInput()) {
                    int read = source.read(compressed);
                    if (read <= 0) throw new ZipException("ZIP 压缩流已截断");
                    inflater.setInput(compressed, 0, read);
                } else {
                    throw new ZipException("ZIP 压缩流无法继续");
                }
            }
        } catch (DataFormatException error) {
            /** 解码失败属于内容错误，输入源的磁盘异常不会进入此分支。 */
            var invalid = new ZipException("ZIP 压缩流无效");
            invalid.initCause(error);
            throw invalid;
        }
    }

    /** 释放解压器和原始输入。 */
    @Override
    public void close() throws IOException {
        inflater.end();
        source.close();
    }
}
