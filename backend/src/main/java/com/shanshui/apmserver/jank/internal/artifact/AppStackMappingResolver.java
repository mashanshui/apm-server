package com.shanshui.apmserver.jank.internal.artifact;

import com.shanshui.apmserver.jank.api.InvalidStackArtifactException;
import com.shanshui.apmserver.jank.internal.config.StackParserProperties;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 卡顿解析使用的统一符号表解析入口，并保留一次解析的文件租约。 */
@Service
public class AppStackMappingResolver {

    /** mapping buildId 安全格式。 */
    private static final Pattern MAPPING_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    /** 统一符号表公共接口。 */
    private final SymbolRegistry symbolRegistry;
    /** 仅供既有单元测试构造的旧路径配置，生产 Bean 不使用。 */
    private final StackParserProperties legacyProperties;

    /** 生产构造函数，所有请求均从统一注册表读取。 */
    @Autowired
    public AppStackMappingResolver(SymbolRegistry symbolRegistry) {
        this.symbolRegistry = symbolRegistry;
        this.legacyProperties = null;
    }

    /** 测试构造函数，允许验证路径安全边界。 */
    public AppStackMappingResolver(StackParserProperties properties) {
        this.symbolRegistry = null;
        this.legacyProperties = properties;
    }

    /** 取得一次卡顿解析期间固定的 mapping 文件和租约。 */
    public ResolvedMapping resolve(java.util.UUID appId, String buildId) {
        validateBuildId(buildId);
        if (symbolRegistry != null) {
            return symbolRegistry.acquire(appId, buildId)
                    .map(lease -> new ResolvedMapping(lease.path().toFile(), lease))
                    .orElseGet(() -> new ResolvedMapping(null, null));
        }
        return new ResolvedMapping(resolveLegacy(appId, buildId), null);
    }

    /** 兼容旧单元测试的立即文件查询；生产解析应使用 {@link #resolve}。 */
    public File resolveOptional(java.util.UUID appId, String buildId) {
        ResolvedMapping mapping = resolve(appId, buildId);
        if (mapping.lease() != null) {
            try {
                mapping.lease().close();
            } catch (IOException ignored) {
                // 测试辅助查询无法影响当前响应。
            }
        }
        return mapping.file();
    }

    /** 校验构建标识，阻止路径穿越和跨应用访问。 */
    private void validateBuildId(String buildId) {
        if (buildId == null || buildId.isBlank() || !MAPPING_ID.matcher(buildId).matches()
                || ".".equals(buildId) || "..".equals(buildId)) {
            throw new InvalidStackArtifactException("INVALID_BUILD_ID", "卡顿产物 buildId 格式无效");
        }
    }

    /** 仅在测试构造器下解析旧手工路径。 */
    private File resolveLegacy(java.util.UUID appId, String buildId) {
        if (legacyProperties == null || legacyProperties.getMappingRoot() == null
                || legacyProperties.getMappingRoot().isBlank()) {
            return null;
        }
        try {
            Path root = Path.of(legacyProperties.getMappingRoot()).toAbsolutePath().normalize();
            Path appRoot = root.resolve(appId.toString()).normalize();
            Path mapping = appRoot.resolve(buildId + ".txt").normalize();
            if (!appRoot.startsWith(root) || !mapping.startsWith(appRoot)) {
                throw unsafeMapping();
            }
            if (!Files.exists(root) || !Files.exists(appRoot) || !Files.exists(mapping)) {
                return null;
            }
            Path realRoot = root.toRealPath();
            Path realAppRoot = appRoot.toRealPath();
            Path realMapping = mapping.toRealPath();
            if (!realAppRoot.startsWith(realRoot) || !realMapping.startsWith(realAppRoot)
                    || !Files.isRegularFile(realMapping) || !Files.isReadable(realMapping)) {
                throw unsafeMapping();
            }
            return realMapping.toFile();
        } catch (InvalidStackArtifactException ex) {
            throw ex;
        } catch (InvalidPathException ex) {
            throw new InvalidStackArtifactException("INVALID_BUILD_ID", "卡顿产物 buildId 格式无效");
        } catch (IOException ex) {
            throw unsafeMapping();
        }
    }

    /** 返回安全的旧路径错误。 */
    private InvalidStackArtifactException unsafeMapping() {
        return new InvalidStackArtifactException("INVALID_MAPPING_PATH", "应用 mapping 文件不可用");
    }

    /** 一次卡顿解析固定的 mapping 文件和可选租约。 */
    public record ResolvedMapping(File file, SymbolFileLease lease) {
    }
}
