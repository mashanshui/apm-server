package com.shanshui.apmserver.symbol.internal.archive;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 归档路径、完整性、资源预算及失败清理测试。 */
class NativeArchiveReaderTests {
    /** 合法候选使用随机暂存路径，并在释放后清除。 */
    @Test
    void acceptsCandidatesAndCleansUp(@TempDir Path directory) throws IOException {
        Path input = zip(directory, List.of("arm64/libsample.so", "__MACOSX/._libsample.so"));
        try (NativeArchive archive = reader().read(input, directory, deadline())) {
            assertEquals(1, archive.entries().size());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(archive.entries().getFirst().path()));
        }
        assertClean(directory);
    }

    /** 真实 NDK 夹具经归档读取后仍可识别完整 ELF 身份。 */
    @Test
    void readsActualElfArchive(@TempDir Path directory) throws IOException {
        String fixtureRoot = System.getenv("APM_ELF_FIXTURES");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixtureRoot != null && !fixtureRoot.isBlank(), "未提供临时 ELF 夹具目录");
        Path input = directory.resolve("input.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            for (String abi : List.of("arm64-v8a", "x86_64")) {
                zip.putNextEntry(new ZipEntry(abi + "/libapm_fixture.so"));
                Files.copy(Path.of(fixtureRoot, abi, "libapm_fixture.so"), zip);
                zip.closeEntry();
            }
        }
        try (NativeArchive archive = reader().read(input, directory, deadline())) {
            assertEquals(2, archive.entries().size());
            var inspector = new com.shanshui.apmserver.symbol.internal.elf.ElfInspector();
            for (NativeArchive.Entry entry : archive.entries()) {
                String abi = entry.name().substring(0, entry.name().indexOf('/'));
                var metadata = inspector.inspect(entry.path());
                assertEquals(abi, metadata.abi());
                assertEquals(Files.readString(Path.of(fixtureRoot, abi, "build-id.txt")).strip(), metadata.elfBuildId());
            }
        }
        assertClean(directory);
    }

    /** 路径穿越、链接、重复规范路径、嵌套归档及空候选全批失败。 */
    @Test
    void rejectsUnsafePathsAndLinks(@TempDir Path directory) throws IOException {
        for (List<String> names : List.of(List.of("../evil.so"), List.of("/absolute.so"), List.of("C:/evil.so"),
                List.of("a\\\\b.so"), List.of("dir/", "dir"), List.of("nested.zip"), List.of(".DS_Store"),
                List.of("first.so", "../invalid.so"))) {
            Path input = zip(directory, names);
            assertThrows(IOException.class, () -> reader().read(input, directory, deadline()));
            assertClean(directory);
        }
        Path input = directory.resolve("input.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(input)) {
            ZipArchiveEntry link = new ZipArchiveEntry("link.so");
            link.setUnixMode(0120777);
            zip.putArchiveEntry(link);
            zip.write(new byte[]{1, 2, 3});
            zip.closeArchiveEntry();
        }
        assertThrows(IOException.class, () -> reader().read(input, directory, deadline()));
        assertClean(directory);
    }

    /** 本地头、CRC、截止时间和中断导致失败时不能保留暂存。 */
    @Test
    void rejectsCorruptionAndInterrupt(@TempDir Path directory) throws IOException {
        Path input = zip(directory, List.of("lib.so"));
        byte[] original = Files.readAllBytes(input);
        byte[] badName = original.clone();
        badName[30] = 'x';
        Files.write(input, badName);
        assertThrows(IOException.class, () -> reader().read(input, directory, deadline()));
        assertClean(directory);
        byte[] badCrc = original.clone();
        int central = findSignature(badCrc, 0x02014b50);
        ByteBuffer.wrap(badCrc).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 16, 12345);
        Files.write(input, badCrc);
        assertThrows(IOException.class, () -> reader().read(input, directory, deadline()));
        assertClean(directory);
        Files.write(input, original);
        assertThrows(java.io.InterruptedIOException.class, () -> reader().read(input, directory, System.nanoTime() - 1));
        assertClean(directory);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> reader().read(input, directory, deadline()));
            org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertClean(directory);
    }

    /** 内容伪装的嵌套 ZIP、加密标志和伪造解压大小必须拒绝。 */
    @Test
    void rejectsNestedArchiveEncryptionAndFakeSizes(@TempDir Path directory) throws IOException {
        Path input = directory.resolve("input.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("disguised.so"));
            zip.write(new byte[]{'P', 'K', 3, 4, 1, 2});
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> reader().read(input, directory, deadline()));
        assertClean(directory);
        zip(directory, List.of("lib.so"));
        byte[] original = Files.readAllBytes(input);
        int central = findSignature(original, 0x02014b50);
        byte[] encrypted = original.clone();
        ByteBuffer.wrap(encrypted).order(ByteOrder.LITTLE_ENDIAN).putShort(central + 8, (short) 1);
        Files.write(input, encrypted);
        assertThrows(IOException.class, () -> reader().read(input, directory, deadline()));
        assertClean(directory);
        byte[] fakeSize = original.clone();
        ByteBuffer.wrap(fakeSize).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 24, 100);
        Files.write(input, fakeSize);
        assertThrows(IOException.class, () -> reader().read(input, directory, deadline()));
        assertClean(directory);
    }

    /** 高压缩率文件按真实解压大小限制，并清理所有临时数据。 */
    @Test
    void rejectsCompressedBomb(@TempDir Path directory) throws IOException {
        Path input = directory.resolve("input.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("bomb.so"));
            byte[] buffer = new byte[8192];
            for (int index = 0; index < 16385; index++) zip.write(buffer);
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> reader().read(input, directory, deadline()));
        assertClean(directory);
    }

    /** 创建包含指定条目的标准测试归档。 */
    private Path zip(Path directory, List<String> names) throws IOException {
        Path path = directory.resolve("input.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zip.write(new byte[]{1, 2, 3});
                zip.closeEntry();
            }
        }
        return path;
    }

    /** 用于测试的短期有效期限。 */
    private long deadline() {
        return System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    }

    /** 创建被测归档读取器。 */
    private NativeArchiveReader reader() {
        return new NativeArchiveReader();
    }

    /** 失败或释放后只保留测试输入 ZIP。 */
    private void assertClean(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(List.of("input.zip"), files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    /** 查找测试 ZIP 中的固定签名。 */
    private int findSignature(byte[] bytes, int signature) {
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index <= bytes.length - 4; index++) if (data.getInt(index) == signature) return index;
        throw new AssertionError("测试归档缺少签名");
    }
}
