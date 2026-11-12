package com.shanshui.apmserver.symbol.internal.elf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** 仅读取 ELF 表和 note，不加载或执行上传的二进制文件。 */
public final class ElfInspector {
    /** 阶段日志不包含文件路径或内容。 */
    private static final Logger log = LoggerFactory.getLogger(ElfInspector.class);
    /** 段表及程序表项的资源上限。 */
    private static final int MAX_HEADERS = 4096;
    /** 名称表和 note 的单项读取上限。 */
    private static final int MAX_TABLE_BYTES = 1024 * 1024;
    /** 完整符号表最大项数及名称累计检查预算。 */
    private static final int MAX_SYMBOLS = 1_000_000;
    private static final long MAX_SYMBOL_NAME_BYTES = 16L * 1024 * 1024;
    /** 单个符号或段名称的读取上限。 */
    private static final int MAX_NAME_BYTES = 4096;

    /** 校验共享库并提取 ABI、完整 Build ID 和符号信息。 */
    public ElfMetadata inspect(Path path) throws IOException {
        return inspect(path, Long.MAX_VALUE);
    }

    /** 使用上传流程的统一截止时间检查 ELF，超时和中断均立即失败。 */
    public ElfMetadata inspect(Path path, long deadlineNanos) throws IOException {
        try (ElfFileReader reader = new ElfFileReader(path, deadlineNanos)) {
            ElfMetadata metadata = inspectFile(reader);
            log.info("ELF 元数据校验完成：abi={} 函数符号={} 调试信息={} 行信息={}",
                    metadata.abi(), metadata.functionSymbols(), metadata.debugInfo(), metadata.debugLine());
            return metadata;
        } catch (IOException | RuntimeException error) {
            log.warn("ELF 元数据校验失败");
            if (error instanceof IOException io) throw io;
            if (!(error instanceof IllegalArgumentException || error instanceof IndexOutOfBoundsException)) throw (RuntimeException) error;
            /** 越界或无效字段属于内容错误，底层读取 IOException 保留原类别。 */
            var invalid = new ElfValidationException("INVALID_SO");
            invalid.initCause(error);
            throw invalid;
        }
    }

    /** 解读文件头与表，先建立有界索引再读取具体材料。 */
    private ElfMetadata inspectFile(ElfFileReader reader) throws IOException {
        ByteBuffer ident = reader.read(0, 16);
        if (ident.get(0) != 0x7f || ident.get(1) != 'E' || ident.get(2) != 'L' || ident.get(3) != 'F'
                || (ident.get(4) != 1 && ident.get(4) != 2) || ident.get(5) != 1 || ident.get(6) != 1) {
            throw invalid();
        }
        boolean elf64 = ident.get(4) == 2;
        ByteBuffer header = reader.read(0, elf64 ? 64 : 52);
        int machine = Short.toUnsignedInt(header.getShort(18));
        String abi = abi(elf64, machine);
        if (Short.toUnsignedInt(header.getShort(16)) != 3 || header.getInt(20) != 1
                || Short.toUnsignedInt(header.getShort(elf64 ? 52 : 40)) != (elf64 ? 64 : 52)) throw invalid();
        long programOffset = word(header, elf64 ? 32 : 28, elf64);
        long sectionOffset = word(header, elf64 ? 40 : 32, elf64);
        int programSize = Short.toUnsignedInt(header.getShort(elf64 ? 54 : 42));
        int programCount = Short.toUnsignedInt(header.getShort(elf64 ? 56 : 44));
        int sectionSize = Short.toUnsignedInt(header.getShort(elf64 ? 58 : 46));
        int sectionCount = Short.toUnsignedInt(header.getShort(elf64 ? 60 : 48));
        int namesIndex = Short.toUnsignedInt(header.getShort(elf64 ? 62 : 50));
        if (programCount > MAX_HEADERS || sectionCount == 0 || sectionCount > MAX_HEADERS
                || namesIndex == 0 || namesIndex >= sectionCount || sectionSize != (elf64 ? 64 : 40)
                || (programCount > 0 && programSize != (elf64 ? 56 : 32))) throw invalid();
        reader.checkRange(sectionOffset, (long) sectionSize * sectionCount);
        reader.checkRange(programOffset, (long) programSize * programCount);
        List<Section> sections = new ArrayList<>(sectionCount);
        for (int index = 0; index < sectionCount; index++) {
            ByteBuffer item = reader.read(sectionOffset + (long) index * sectionSize, sectionSize);
            Section section = new Section(Integer.toUnsignedLong(item.getInt(0)), item.getInt(4),
                    word(item, elf64 ? 24 : 16, elf64), word(item, elf64 ? 32 : 20, elf64),
                    Integer.toUnsignedLong(item.getInt(elf64 ? 40 : 24)), word(item, elf64 ? 56 : 36, elf64));
            if (section.type() != 8) reader.checkRange(section.offset(), section.size());
            sections.add(section);
        }
        Section names = sections.get(namesIndex);
        if (names.type() != 3 || names.size() > MAX_TABLE_BYTES) throw invalid();
        ByteBuffer nameTable = reader.read(names.offset(), (int) names.size());
        byte[] buildId = null;
        boolean symbols = false;
        boolean debugInfo = false;
        boolean debugLine = false;
        for (Section section : sections) {
            String name = name(nameTable, section.nameOffset());
            if (section.type() == 7) buildId = merge(buildId, notes(reader, section.offset(), section.size()));
            if (".symtab".equals(name)) symbols |= symbols(reader, sections, section, elf64);
            if (".debug_info".equals(name) && section.type() == 1 && section.size() > 0) debugInfo = true;
            if (".debug_line".equals(name) && section.type() == 1 && section.size() > 0) debugLine = true;
        }
        for (int index = 0; index < programCount; index++) {
            ByteBuffer program = reader.read(programOffset + (long) index * programSize, programSize);
            long offset = word(program, elf64 ? 8 : 4, elf64);
            long size = word(program, elf64 ? 32 : 16, elf64);
            reader.checkRange(offset, size);
            if (program.getInt(0) == 4) buildId = merge(buildId, notes(reader, offset, size));
        }
        if (buildId == null) throw new ElfValidationException("SO_BUILD_ID_MISSING");
        if (!symbols && !debugInfo && !debugLine) throw new ElfValidationException("SO_SYMBOLS_MISSING");
        return new ElfMetadata(abi, HexFormat.of().formatHex(buildId), symbols, debugInfo, debugLine);
    }

    /** 转换受支持的 ELF 类型和处理器组合。 */
    private String abi(boolean elf64, int machine) throws IOException {
        if (!elf64 && machine == 40) return "armeabi-v7a";
        if (elf64 && machine == 183) return "arm64-v8a";
        if (!elf64 && machine == 3) return "x86";
        if (elf64 && machine == 62) return "x86_64";
        throw new ElfValidationException("UNSUPPORTED_SO_FORMAT");
    }

    /** 读取 32 位无符号值或可由文件偏移表示的 64 位值。 */
    private long word(ByteBuffer buffer, int offset, boolean elf64) throws IOException {
        long value = elf64 ? buffer.getLong(offset) : Integer.toUnsignedLong(buffer.getInt(offset));
        if (value < 0) throw invalid();
        return value;
    }

    /** 读取受限 note 表，完整保留 GNU Build ID 并检查重复值。 */
    private byte[] notes(ElfFileReader reader, long offset, long size) throws IOException {
        if (size > MAX_TABLE_BYTES) throw invalid();
        ByteBuffer data = reader.read(offset, (int) size);
        byte[] found = null;
        while (data.hasRemaining()) {
            reader.checkDeadline();
            if (data.remaining() < 12) throw invalid();
            long nameSize = Integer.toUnsignedLong(data.getInt());
            long descSize = Integer.toUnsignedLong(data.getInt());
            int type = data.getInt();
            long namePadded = (nameSize + 3) & ~3L;
            long descPadded = (descSize + 3) & ~3L;
            if (namePadded > data.remaining() || descPadded > data.remaining() - namePadded) throw invalid();
            int nameStart = data.position();
            boolean gnu = nameSize == 4 && data.get(nameStart) == 'G' && data.get(nameStart + 1) == 'N'
                    && data.get(nameStart + 2) == 'U' && data.get(nameStart + 3) == 0;
            data.position(nameStart + (int) namePadded);
            if (gnu && type == 3) {
                if (descSize < 1 || descSize > 64) throw invalid();
                byte[] current = new byte[(int) descSize];
                data.get(current);
                found = merge(found, current);
                data.position(data.position() + (int) (descPadded - descSize));
            } else {
                data.position(data.position() + (int) descPadded);
            }
        }
        return found;
    }

    /** 合并不同 note 来源；冲突不得选取其中一个。 */
    private byte[] merge(byte[] left, byte[] right) throws IOException {
        if (left != null && right != null && !Arrays.equals(left, right)) throw invalid();
        return left != null ? left : right;
    }

    /** 检查完整符号表及关联字符串表中的已定义函数。 */
    private boolean symbols(ElfFileReader reader, List<Section> sections, Section table, boolean elf64) throws IOException {
        int entrySize = elf64 ? 24 : 16;
        if (table.type() != 2 || table.entrySize() != entrySize || table.size() % entrySize != 0
                || table.size() / entrySize > MAX_SYMBOLS || table.link() >= sections.size()) throw invalid();
        Section strings = sections.get((int) table.link());
        if (strings.type() != 3 || strings.size() > MAX_TABLE_BYTES) throw invalid();
        ByteBuffer stringTable = reader.read(strings.offset(), (int) strings.size());
        boolean found = false;
        long checkedNameBytes = 0;
        for (long index = 0; index < table.size() / entrySize; index++) {
            ByteBuffer symbol = reader.read(table.offset() + index * entrySize, entrySize);
            long nameOffset = Integer.toUnsignedLong(symbol.getInt(0));
            int info = Byte.toUnsignedInt(symbol.get(elf64 ? 4 : 12));
            int sectionIndex = Short.toUnsignedInt(symbol.getShort(elf64 ? 6 : 14));
            String symbolName = name(stringTable, nameOffset);
            checkedNameBytes += symbolName.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (checkedNameBytes > MAX_SYMBOL_NAME_BYTES) throw invalid();
            if ((info & 15) == 2 && sectionIndex != 0 && sectionIndex < 0xff00 && sectionIndex >= sections.size()) throw invalid();
            if ((info & 15) == 2 && sectionIndex != 0 && !symbolName.isEmpty()) found = true;
        }
        return found;
    }

    /** 解析以零结尾的表内名称，不接受越界或未终止字符串。 */
    private String name(ByteBuffer table, long offset) throws IOException {
        if (offset >= table.limit()) throw invalid();
        int start = (int) offset;
        int end = start;
        while (end < table.limit() && end - start <= MAX_NAME_BYTES && table.get(end) != 0) end++;
        if (end == table.limit() || end - start > MAX_NAME_BYTES) throw invalid();
        byte[] bytes = new byte[end - start];
        ByteBuffer copy = table.duplicate();
        copy.position(start);
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 创建不包含输入数据的统一格式错误。 */
    private IOException invalid() {
        return new ElfValidationException("INVALID_SO");
    }

    /** 有界段表中的必要属性。 */
    private record Section(
            /** 名称在段名称表中的偏移。 */ long nameOffset,
            /** ELF 段类型。 */ int type,
            /** 文件内偏移。 */ long offset,
            /** 文件内大小。 */ long size,
            /** 关联段表下标。 */ long link,
            /** 固定表项大小。 */ long entrySize) {
    }
}
