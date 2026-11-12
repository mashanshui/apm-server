package com.shanshui.apmserver.symbol.internal.elf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用 NDK 生成的实际四 ABI 文件验证只读 ELF 检测。 */
class ElfInspectorTests {
    /** 对照四种 ABI、完整 SHA-1 Build ID 和实际符号信息。 */
    @Test
    void readsFourAbis() throws IOException {
        Path root = fixtureDirectory();
        for (String abi : List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
            Path abiDirectory = root.resolve(abi);
            ElfMetadata result = new ElfInspector().inspect(abiDirectory.resolve("libapm_fixture.so"));
            assertEquals(abi, result.abi());
            assertEquals(Files.readString(abiDirectory.resolve("build-id.txt")).strip(), result.elfBuildId());
            assertTrue(result.functionSymbols());
            assertTrue(result.debugInfo());
            assertTrue(result.debugLine());
        }
    }

    /** 非 ELF、截断文件和溢出的段表偏移必须被拒绝。 */
    @Test
    void rejectsDamagedHeaders(@TempDir Path directory) throws IOException {
        Path fixture = fixtureDirectory().resolve("arm64-v8a/libapm_fixture.so");
        byte[] original = Files.readAllBytes(fixture);
        Path target = directory.resolve("bad.so");
        Files.write(target, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> new ElfInspector().inspect(target));
        byte[] damaged = original.clone();
        java.util.Arrays.fill(damaged, 40, 48, (byte) 0xff);
        Files.write(target, damaged);
        assertThrows(IOException.class, () -> new ElfInspector().inspect(target));
    }

    /** 64 字节 Build ID 完整保留，不能截断为较短标识。 */
    @Test
    void preservesLongBuildId() throws IOException {
        Path abiDirectory = fixtureDirectory().resolve("arm64-v8a");
        String expected = Files.readString(abiDirectory.resolve("long-build-id.txt")).strip();
        assertEquals(128, expected.length());
        assertEquals(expected, new ElfInspector().inspect(abiDirectory.resolve("libapm_long_id.so")).elfBuildId());
    }

    /** 缺失 GNU Build ID 不能用其他信息代替。 */
    @Test
    void rejectsMissingBuildId(@TempDir Path directory) throws IOException {
        byte[] bytes = Files.readAllBytes(fixtureDirectory().resolve("arm64-v8a/libapm_fixture.so"));
        byte[] marker = new byte[]{'G', 'N', 'U', 0};
        for (int index = 0; index <= bytes.length - marker.length; index++) {
            if (bytes[index] == marker[0] && bytes[index + 1] == marker[1]
                    && bytes[index + 2] == marker[2] && bytes[index + 3] == marker[3]) bytes[index] = 'X';
        }
        Path target = directory.resolve("no-id.so");
        Files.write(target, bytes);
        assertThrows(IOException.class, () -> new ElfInspector().inspect(target));
    }

    /** 剥离后的导出表不足以证明包含可用完整符号。 */
    @Test
    void rejectsDynamicSymbolsOnly() throws IOException {
        Path fixture = fixtureDirectory().resolve("arm64-v8a/libapm_stripped.so");
        assertThrows(IOException.class, () -> new ElfInspector().inspect(fixture));
    }

    /** 损坏 note、段名称表和符号表的元数据必须拒绝。 */
    @Test
    void rejectsCorruptedSections(@TempDir Path directory) throws IOException {
        byte[] original = Files.readAllBytes(fixtureDirectory().resolve("arm64-v8a/libapm_fixture.so"));
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(original).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int sectionOffset = Math.toIntExact(header.getLong(40));
        int sectionCount = Short.toUnsignedInt(header.getShort(60));
        int namesIndex = Short.toUnsignedInt(header.getShort(62));
        int namesOffset = Math.toIntExact(header.getLong(sectionOffset + namesIndex * 64 + 24));
        int note = section(original, sectionOffset, sectionCount, namesOffset, ".note.gnu.build-id");
        int symbols = section(original, sectionOffset, sectionCount, namesOffset, ".symtab");
        Path target = directory.resolve("bad.so");
        byte[] badNote = original.clone();
        java.nio.ByteBuffer.wrap(badNote).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(Math.toIntExact(header.getLong(note + 24)), Integer.MAX_VALUE);
        Files.write(target, badNote);
        assertThrows(IOException.class, () -> new ElfInspector().inspect(target));
        byte[] badTable = original.clone();
        java.nio.ByteBuffer.wrap(badTable).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(symbols + 56, 1);
        Files.write(target, badTable);
        assertThrows(IOException.class, () -> new ElfInspector().inspect(target));
        byte[] badName = original.clone();
        java.nio.ByteBuffer.wrap(badName).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(symbols, Integer.MAX_VALUE);
        Files.write(target, badName);
        assertThrows(IOException.class, () -> new ElfInspector().inspect(target));
    }

    /** 从测试夹具定位指定名称的 64 位段表项。 */
    private int section(byte[] bytes, int start, int count, int names, String target) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < count; index++) {
            int entry = start + index * 64;
            int nameStart = names + buffer.getInt(entry);
            int end = nameStart;
            while (bytes[end] != 0) end++;
            if (target.equals(new String(bytes, nameStart, end - nameStart, java.nio.charset.StandardCharsets.UTF_8))) return entry;
        }
        throw new AssertionError("夹具缺少目标段");
    }

    /** 按显式输入使用临时夹具，未准备 NDK 夹具时标记测试跳过。 */
    private Path fixtureDirectory() {
        String value = System.getenv("APM_ELF_FIXTURES");
        assumeTrue(value != null && !value.isBlank(), "未提供临时 ELF 夹具目录");
        return Path.of(value);
    }
}
