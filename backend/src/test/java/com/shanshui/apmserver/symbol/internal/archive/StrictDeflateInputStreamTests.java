package com.shanshui.apmserver.symbol.internal.archive;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 检查 DEFLATE 流必须精确消费声明的压缩内容。 */
class StrictDeflateInputStreamTests {
    /** 完整流可解压，追加无效数据和截断流都必须拒绝。 */
    @Test
    void rejectsTrailingAndTruncatedCompressedData() throws IOException {
        byte[] payload = new byte[]{1, 2, 3, 4};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(6, true);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(output, deflater)) {
            compressed.write(payload);
        } finally {
            deflater.end();
        }
        byte[] correct = output.toByteArray();
        try (var input = new StrictDeflateInputStream(new ByteArrayInputStream(correct), correct.length)) {
            assertArrayEquals(payload, input.readAllBytes());
        }
        byte[] trailing = java.util.Arrays.copyOf(correct, correct.length + 1);
        try (var input = new StrictDeflateInputStream(new ByteArrayInputStream(trailing), trailing.length)) {
            assertThrows(IOException.class, input::readAllBytes);
        }
        byte[] truncated = java.util.Arrays.copyOf(correct, correct.length - 1);
        try (var input = new StrictDeflateInputStream(new ByteArrayInputStream(truncated), truncated.length)) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }
}
