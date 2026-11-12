package com.shanshui.apmserver.symbol.internal.archive;

import org.apache.commons.compress.archivers.zip.ZipShort;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.zip.ZipException;
import java.nio.channels.SeekableByteChannel;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/** 有界读取普通单卷 ZIP，不能把归档路径直接写入文件系统。 */
public final class NativeArchiveReader {
    /** 日志只输出阶段和数量。 */
    private static final Logger log = LoggerFactory.getLogger(NativeArchiveReader.class);
    /** 每个文件、解压总量以及候选数的固定首版预算。 */
    private static final long MAX_FILE = 128L * 1024 * 1024;
    private static final long MAX_TOTAL = 512L * 1024 * 1024;
    private static final int MAX_CANDIDATES = 256;
    /** 路径及拷贝缓冲预算。 */
    private static final int MAX_PATH_BYTES = 1024;
    private static final int MAX_DEPTH = 8;
    private static final int BUFFER_SIZE = 8192;

    /** 检查归档并解压至服务端生成的临时文件；失败时清理整批。 */
    public NativeArchive read(Path archive, Path stagingRoot, long deadlineNanos) throws IOException {
        checkDeadline(deadlineNanos);
        ZipDirectoryPreflight.Directory index = new ZipDirectoryPreflight().inspect(archive);
        checkDeadline(deadlineNanos);
        int expectedEntries = index.entries();
        Path directory = Files.createTempDirectory(stagingRoot, "native-archive-");
        List<NativeArchive.Entry> candidates = new ArrayList<>();
        NativeArchive result = new NativeArchive(directory, candidates);
        try (var channel = new NativeArchiveChannel(FileChannel.open(archive, StandardOpenOption.READ));
             ZipFile zip = openZip(channel);
             FileChannel source = FileChannel.open(archive, StandardOpenOption.READ)) {
            Set<String> names = new HashSet<>();
            List<Range> ranges = new ArrayList<>();
            long total = 0;
            int count = 0;
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                checkDeadline(deadlineNanos);
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > expectedEntries) throw invalid();
                String name = validateEntry(entry, names);
                Range range = validateLocal(source, entry, index.offset());
                for (Range previous : ranges) {
                    if (range.start() < previous.end() && previous.start() < range.end()) throw invalid();
                }
                ranges.add(range);
                boolean ignored = entry.isDirectory() || name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store") || name.equals(".DS_Store");
                if (!entry.isDirectory() && name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) throw invalid();
                if (!ignored && !name.endsWith(".so")) throw invalid();
                if (!ignored && candidates.size() == MAX_CANDIDATES) throw invalid();
                Path output = ignored ? null : Files.createTempFile(directory, "candidate-", ".so");
                long actual = copy(zip, entry, output, MAX_TOTAL - total, deadlineNanos);
                total += actual;
                if (!ignored) candidates.add(new NativeArchive.Entry(name, output));
            }
            if (count != expectedEntries || candidates.isEmpty()) throw invalid();
            result = new NativeArchive(directory, candidates);
            log.info("SO 归档校验完成：条目数={} 候选数={} 解压字节数={}", count, candidates.size(), total);
            return result;
        } catch (IOException | RuntimeException error) {
            try {
                result.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            log.warn("SO 归档校验失败，已尝试清理暂存");
            if (error instanceof IOException io) throw io;
            if (!(error instanceof IllegalArgumentException || error instanceof IndexOutOfBoundsException)) throw (RuntimeException) error;
            throw formatError(error);
        }
    }

    /** 库内格式异常可包装为内容错误；真实通道 I/O 已由 NativeArchiveChannel 单独传播。 */
    private ZipFile openZip(SeekableByteChannel channel) throws IOException {
        try { return ZipFile.builder().setSeekableByteChannel(channel).get(); }
        catch (IOException invalid) { throw formatError(invalid); }
    }

    /** 保存格式异常原因供内部排查，对外只使用稳定安全提示。 */
    private ZipException formatError(Throwable cause) {
        /** 不包含客户端文件名、路径或原始库错误消息。 */
        var error = new ZipException("SO 归档格式无效");
        error.initCause(cause);
        return error;
    }

    /** 验证原始路径、Unix 类型和压缩元数据，目录也参与去重。 */
    private String validateEntry(ZipArchiveEntry entry, Set<String> names) throws IOException {
        String name = entry.getName();
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || name.matches("^[A-Za-z]:.*") || name.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES
                || name.codePoints().anyMatch(Character::isISOControl) || entry.isUnixSymlink()
                || entry.getGeneralPurposeBit().usesEncryption() || entry.getGeneralPurposeBit().usesStrongEncryption()
                || (entry.getRawFlag() & ~0x080e) != 0 || entry.getUnparseableExtraFieldData() != null
                || (entry.isDirectory() && entry.getSize() != 0) || entry.getDiskNumberStart() != 0 || entry.getExtraField(new ZipShort(1)) != null
                || (entry.getMethod() != 0 && entry.getMethod() != 8) || entry.getSize() < 0 || entry.getCompressedSize() < 0
                || entry.getSize() > MAX_TOTAL || entry.getCompressedSize() > 256L * 1024 * 1024) throw invalid();
        String normalized = entry.isDirectory() && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String[] components = normalized.split("/", -1);
        if (components.length > MAX_DEPTH) throw invalid();
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) throw invalid();
        }
        if (!names.add(normalized)) throw invalid();
        int mode = entry.getUnixMode();
        int type = mode & 0170000;
        if (type != 0 && type != (entry.isDirectory() ? 0040000 : 0100000)) throw invalid();
        return name;
    }

    /** 检查本地文件头与中央目录的标志、压缩方式、名称和长度。 */
    private Range validateLocal(FileChannel source, ZipArchiveEntry entry, long directoryOffset) throws IOException {
        long offset = entry.getLocalHeaderOffset();
        ByteBuffer header = read(source, offset, 30);
        if (header.getInt(0) != 0x04034b50 || Short.toUnsignedInt(header.getShort(6)) != entry.getRawFlag()
                || Short.toUnsignedInt(header.getShort(8)) != entry.getMethod()) throw invalid();
        int nameLength = Short.toUnsignedInt(header.getShort(26));
        int extraLength = Short.toUnsignedInt(header.getShort(28));
        if (nameLength == 0 || nameLength > MAX_PATH_BYTES) throw invalid();
        ByteBuffer details = read(source, offset + 30, nameLength + extraLength);
        byte[] rawName = new byte[nameLength];
        details.get(rawName);
        if (!Arrays.equals(rawName, entry.getRawName())) throw invalid();
        while (details.hasRemaining()) {
            if (details.remaining() < 4) throw invalid();
            int type = Short.toUnsignedInt(details.getShort());
            int size = Short.toUnsignedInt(details.getShort());
            if (size > details.remaining() || type == 1) throw invalid();
            details.position(details.position() + size);
        }
        long crc = Integer.toUnsignedLong(header.getInt(14));
        long compressed = Integer.toUnsignedLong(header.getInt(18));
        long size = Integer.toUnsignedLong(header.getInt(22));
        boolean descriptor = entry.getGeneralPurposeBit().usesDataDescriptor();
        if ((!descriptor && (crc != entry.getCrc() || compressed != entry.getCompressedSize() || size != entry.getSize()))
                || (descriptor && ((crc != 0 && crc != entry.getCrc())
                || (compressed != 0 && compressed != entry.getCompressedSize())
                || (size != 0 && size != entry.getSize())))) throw invalid();
        long dataOffset = offset + 30L + nameLength + extraLength;
        if (entry.getDataOffset() != dataOffset || dataOffset > directoryOffset
                || entry.getCompressedSize() > directoryOffset - dataOffset) throw invalid();
        long dataEnd = dataOffset + entry.getCompressedSize();
        if (descriptor) {
            long descriptorOffset = dataOffset + entry.getCompressedSize();
            if (descriptorOffset > directoryOffset || directoryOffset - descriptorOffset < 12) throw invalid();
            ByteBuffer first = read(source, descriptorOffset, 4);
            boolean signature = first.getInt(0) == 0x08074b50;
            int descriptorSize = signature ? 16 : 12;
            if (directoryOffset - descriptorOffset < descriptorSize) throw invalid();
            ByteBuffer descriptorBytes = read(source, descriptorOffset, descriptorSize);
            int base = signature ? 4 : 0;
            if (Integer.toUnsignedLong(descriptorBytes.getInt(base)) != entry.getCrc()
                    || Integer.toUnsignedLong(descriptorBytes.getInt(base + 4)) != entry.getCompressedSize()
                    || Integer.toUnsignedLong(descriptorBytes.getInt(base + 8)) != entry.getSize()) throw invalid();
            dataEnd += descriptorSize;
        }
        return new Range(offset, dataEnd);
    }

    /** 按实际字节计算 CRC 和预算，包括被忽略的文件。 */
    private long copy(ZipFile zip, ZipArchiveEntry entry, Path output, long remaining, long deadlineNanos) throws IOException {
        CRC32 crc = new CRC32();
        long actual = 0;
        if (entry.getMethod() == 0 && entry.getSize() != entry.getCompressedSize()) throw invalid();
        InputStream raw = zip.getRawInputStream(entry);
        if (raw == null) throw invalid();
        try (InputStream input = entry.getMethod() == 8
                     ? new StrictDeflateInputStream(raw, entry.getCompressedSize()) : raw;
             OutputStream sink = output == null ? OutputStream.nullOutputStream() : Files.newOutputStream(output)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            byte[] prefix = new byte[4];
            int prefixLength = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkDeadline(deadlineNanos);
                int captured = Math.min(prefix.length - prefixLength, count);
                System.arraycopy(buffer, 0, prefix, prefixLength, captured);
                prefixLength += captured;
                if (prefixLength == 4 && prefix[0] == 'P' && prefix[1] == 'K'
                        && ((prefix[2] == 3 && prefix[3] == 4) || (prefix[2] == 5 && prefix[3] == 6)
                        || (prefix[2] == 7 && prefix[3] == 8))) throw invalid();
                actual += count;
                if (actual > remaining || (output != null && actual > MAX_FILE)) throw new ArchiveLimitException();
                crc.update(buffer, 0, count);
                sink.write(buffer, 0, count);
            }
        }
        if (actual != entry.getSize() || crc.getValue() != entry.getCrc()) throw invalid();
        return actual;
    }

    /** 检查上传期限及线程中断。 */
    private void checkDeadline(long deadlineNanos) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0) {
            throw new InterruptedIOException("SO 归档检查已超时或中断");
        }
    }

    /** 安全读取固定长度的本地头及其字段。 */
    private ByteBuffer read(FileChannel channel, long offset, int length) throws IOException {
        if (offset < 0 || offset > channel.size() || length > channel.size() - offset) throw invalid();
        ByteBuffer data = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (data.hasRemaining()) {
            int read = channel.read(data, offset + data.position());
            if (read <= 0) throw invalid();
        }
        return data.flip();
    }

    /** 单条本地记录及压缩数据所占范围。 */
    private record Range(/** 起始偏移。 */ long start, /** 结束偏移（不含）。 */ long end) {
    }

    /** 不回显文件名或输入内容。 */
    private ZipException invalid() {
        return new ZipException("SO 归档格式或资源预算无效");
    }
}
