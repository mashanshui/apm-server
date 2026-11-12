package com.shanshui.apmserver.symbol.internal.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ZIP 索引创建前执行的尾部与中央目录边界测试。 */
class ZipDirectoryPreflightTests {
    /** 普通单卷归档通过，伪造 ZIP64、多卷及目录边界均拒绝。 */
    @Test
    void validatesDirectoryBeforeIndexing(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("fixture.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("lib.so"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        ZipDirectoryPreflight preflight = new ZipDirectoryPreflight();
        assertEquals(1, preflight.inspect(path).entries());
        byte[] original = Files.readAllBytes(path);
        int end = original.length - 22;
        byte[] zip64 = original.clone();
        ByteBuffer.wrap(zip64).order(ByteOrder.LITTLE_ENDIAN).putInt(end + 12, -1);
        Files.write(path, zip64);
        assertThrows(IOException.class, () -> preflight.inspect(path));
        byte[] multiDisk = original.clone();
        ByteBuffer.wrap(multiDisk).order(ByteOrder.LITTLE_ENDIAN).putShort(end + 4, (short) 1);
        Files.write(path, multiDisk);
        assertThrows(IOException.class, () -> preflight.inspect(path));
        byte[] badLength = original.clone();
        ByteBuffer.wrap(badLength).order(ByteOrder.LITTLE_ENDIAN).putInt(end + 12, Integer.MAX_VALUE);
        Files.write(path, badLength);
        assertThrows(IOException.class, () -> preflight.inspect(path));
    }
}
