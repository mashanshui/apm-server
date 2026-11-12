package com.shanshui.apmserver.symbol.internal.web;

import com.shanshui.apmserver.symbol.api.SymbolValidationException;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.http.fileupload.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.DefaultMultipartHttpServletRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** 复用容器流式 multipart 解析，实际读取执行单请求截止时间和独立文本预算。 */
public final class NativeMultipartBody implements AutoCloseable {
    /** 统一受控暂存卷。 */
    private final SymbolFileStore store;
    /** 只属于本次请求的临时文件，结束时删除。 */
    private final List<SymbolFileStore.StagedFile> staged = new ArrayList<>();
    /** 注入请求完整生命周期内的文件卷。 */
    public NativeMultipartBody(SymbolFileStore store) { this.store = store; }
    /** 转换成 Spring multipart 请求，DispatcherServlet 不再重复解析原始正文。 */
    public DefaultMultipartHttpServletRequest parse(HttpServletRequest request, long deadline) throws IOException {
        /** Tomcat 自带的流式解析器，限制请求总量、单 part 和头大小。 */
        var parser = new FileUpload();
        parser.setSizeMax(260L * 1024 * 1024);
        parser.setFileSizeMax(256L * 1024 * 1024);
        parser.setPartHeaderSizeMax(8192);
        parser.setHeaderEncoding("UTF-8");
        /** 文本与文件分开保存，全部字段必须唯一。 */
        var files = new LinkedMultiValueMap<String, MultipartFile>();
        var fields = new LinkedHashMap<String, String[]>();
        var types = new HashMap<String, String>();
        var names = new HashSet<String>();
        /** 每次底层网络读取前后都检查同一总期限。 */
        InputStream body = new TimedInput(request.getInputStream(), deadline);
        try {
            var iterator = parser.getItemIterator(new UploadContext() {
                /** 保留原始 multipart 边界。 */
                public String getContentType() { return request.getContentType(); }
                /** 所有文本固定 UTF-8。 */
                public String getCharacterEncoding() { return "UTF-8"; }
                /** 长度只作提前拒绝，解析器仍按实际读取计数。 */
                public long contentLength() { return request.getContentLengthLong(); }
                /** 原始流不会调用 getParameter/getParts。 */
                public InputStream getInputStream() { return body; }
            });
            while (iterator.hasNext()) {
                var item = iterator.next();
                String name = item.getFieldName();
                if (names.size() >= 6 || !names.add(name) || !Set.of("file", "appVersion", "targetAbi", "targetElfBuildId", "expectedEntries").contains(name)) {
                    throw new SymbolValidationException("SYMBOL_TYPE_MISMATCH", "SO 字段不适用、重复或过多", 400);
                }
                types.put(name, item.getContentType() == null ? "text/plain" : item.getContentType());
                if ("file".equals(name)) {
                    var value = store.stage(new TimedInput(item.openStream(), deadline), 256L * 1024 * 1024);
                    staged.add(value);
                    files.add(name, new DiskPart(value, item.getName()));
                } else {
                    try (var input = new TimedInput(item.openStream(), deadline)) {
                        byte[] bytes = input.readNBytes(65537);
                        if (bytes.length > 65536) throw new SymbolValidationException("INVALID_SYMBOL_CONFIRMATION", "SO 文本字段过大", 400);
                        fields.put(name, new String[]{new String(bytes, StandardCharsets.UTF_8)});
                    }
                }
            }
            return new DefaultMultipartHttpServletRequest(request, files, fields, types);
        } catch (org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException
                 | org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException tooLarge) {
            throw new SymbolValidationException("SYMBOL_ARCHIVE_TOO_LARGE", "SO 请求超过字节预算", 413);
        } catch (com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException failure) {
            // 容器流将限额异常包装为 IOException，不能误报成磁盘不可用。
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
                if (cause instanceof org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException
                        || cause instanceof org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException) {
                    throw new SymbolValidationException("SYMBOL_ARCHIVE_TOO_LARGE", "SO 请求超过字节预算", 413);
                }
            }
            throw failure;
        } catch (FileUploadException invalid) {
            throw new SymbolValidationException("INVALID_SYMBOL_ARCHIVE", "multipart 格式无效", 400);
        }
    }
    /** 无论解析、权限、校验或事务结果，均释放本次请求的临时 ZIP。 */
    @Override public void close() { for (var value : staged) store.discard(value); }
    /** 保留单份受控磁盘文件，服务层直接使用同一 ZIP，避免重复压缩体暂存。 */
    public static final class DiskPart implements MultipartFile {
        /** 暂存身份不受客户端路径影响。 */
        private final SymbolFileStore.StagedFile value;
        /** 名称仅用于显示。 */
        private final String name;
        /** 创建只读磁盘 part。 */
        private DiskPart(SymbolFileStore.StagedFile value, String name) { this.value = value; this.name = name; }
        /** 服务层复用本次受控临时 ZIP。 */
        public SymbolFileStore.StagedFile stagedFile() { return value; }
        /** 固定表单字段。 */
        public String getName() { return "file"; }
        /** 不作为存储键使用。 */
        public String getOriginalFilename() { return name; }
        /** ZIP 内容类型。 */
        public String getContentType() { return "application/zip"; }
        /** 根据实际大小判断。 */
        public boolean isEmpty() { return value.sizeBytes() == 0; }
        /** 返回实际读取大小。 */
        public long getSize() { return value.sizeBytes(); }
        /** 禁止误把整个大 ZIP 加载到堆中。 */
        public byte[] getBytes() throws IOException { throw new IOException("SO ZIP 只允许流式读取"); }
        /** 每次以同一暂存字节打开输入流。 */
        public InputStream getInputStream() throws IOException { return Files.newInputStream(value.path()); }
        /** 受控复制，不使用客户端路径。 */
        public void transferTo(File target) throws IOException { Files.copy(value.path(), target.toPath()); }
    }
    /** 单次底层阻塞仍由 Servlet 上传超时保护，持续慢流由总期限限制。 */
    private static final class TimedInput extends FilterInputStream {
        /** 许可统一截止时间。 */
        private final long deadline;
        /** 绑定本次读取流和期限。 */
        private TimedInput(InputStream input, long deadline) { super(input); this.deadline = deadline; }
        /** 每次读取前后执行同一检查。 */
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            check(); int count = in.read(bytes, offset, length); check(); return count;
        }
        /** 单字节读取同样受保护。 */
        @Override public int read() throws IOException { check(); int value = in.read(); check(); return value; }
        /** 超时或中断不继续写入后续字节。 */
        private void check() throws IOException {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IOException("SO 接收超时或中断");
        }
    }
}
