package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.StackParserProperties;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Service
public class AppStackMappingResolver {

    private static final Pattern MAPPING_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final StackParserProperties properties;

    public AppStackMappingResolver(StackParserProperties properties) {
        this.properties = properties;
    }

    public File resolveOptional(java.util.UUID appId, String buildId) {
        if (buildId == null || buildId.isBlank()) {
            throw new InvalidStackArtifactException("INVALID_BUILD_ID", "卡顿产物 buildId 格式无效");
        }
        if (!MAPPING_ID.matcher(buildId).matches() || ".".equals(buildId) || "..".equals(buildId)) {
            throw new InvalidStackArtifactException("INVALID_BUILD_ID", "卡顿产物 buildId 格式无效");
        }
        if (properties.getMappingRoot() == null || properties.getMappingRoot().isBlank()) {
            return null;
        }
        try {
            Path root = Path.of(properties.getMappingRoot()).toAbsolutePath().normalize();
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

    private InvalidStackArtifactException unsafeMapping() {
            return new InvalidStackArtifactException("INVALID_MAPPING_PATH", "应用 mapping 文件不可用");
    }
}
