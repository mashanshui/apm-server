package com.shanshui.apmserver.symbol.internal.elf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 ELF 只读访问的范围和溢出防护。 */
class ElfFileReaderTests {
    /** 仅允许完整落在输入文件内的读取。 */
    @Test
    void rejectsInvalidRanges(@TempDir Path directory) throws IOException {
        Path input = directory.resolve("sample");
        Files.write(input, new byte[]{1, 2, 3, 4});
        try (ElfFileReader reader = new ElfFileReader(input, Long.MAX_VALUE)) {
            assertEquals(2, reader.read(1, 2).get());
            assertThrows(ElfValidationException.class, () -> reader.read(-1, 1));
            assertThrows(IOException.class, () -> reader.read(3, 2));
            assertThrows(IOException.class, () -> reader.read(Long.MAX_VALUE, 1));
            assertThrows(IOException.class, () -> reader.checkRange(1, Long.MAX_VALUE));
        }
    }

    /** 统一期限到达后拒绝新读取，中断不会被吞掉。 */
    @Test
    void rejectsExpiredOrInterruptedReads(@TempDir Path directory) throws IOException {
        Path input = directory.resolve("sample");
        Files.write(input, new byte[]{1});
        assertThrows(java.io.InterruptedIOException.class, () -> new ElfFileReader(input, System.nanoTime() - 1));
        try (ElfFileReader reader = new ElfFileReader(input, Long.MAX_VALUE)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.io.InterruptedIOException.class, () -> reader.read(0, 1));
            } finally {
                Thread.interrupted();
            }
        }
    }
}
