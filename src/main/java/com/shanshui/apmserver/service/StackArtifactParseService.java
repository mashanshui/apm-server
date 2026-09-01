package com.shanshui.apmserver.service;

import com.bytedance.rheatrace.stack.StackParser;
import com.shanshui.apmserver.config.StackParserProperties;
import com.shanshui.apmserver.domain.AppendResult;
import com.shanshui.apmserver.domain.AuthenticatedApp;
import com.shanshui.apmserver.domain.StackArtifactParseResponse;
import com.shanshui.apmserver.domain.StoredEvent;
import com.shanshui.apmserver.repository.EventRepository;
import com.shanshui.apmserver.repository.EventStoreUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

@Service
public class StackArtifactParseService {

    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private final StackParser parser;
    private final ObjectMapper objectMapper;
    private final AppStackMappingResolver mappingResolver;
    private final JankArtifactReportMapper reportMapper;
    private final EventRepository repository;
    private final CrashQualityMetrics metrics;
    private final Semaphore permits;
    private final Clock clock;

    @Autowired
    public StackArtifactParseService(StackParser parser,
                                     ObjectMapper objectMapper,
                                     AppStackMappingResolver mappingResolver,
                                     JankArtifactReportMapper reportMapper,
                                     EventRepository repository,
                                     StackParserProperties properties,
                                     CrashQualityMetrics metrics) {
        this(parser, objectMapper, mappingResolver, reportMapper, repository, properties, metrics, Clock.systemUTC());
    }

    StackArtifactParseService(StackParser parser,
                              ObjectMapper objectMapper,
                              AppStackMappingResolver mappingResolver,
                              JankArtifactReportMapper reportMapper,
                              EventRepository repository,
                              StackParserProperties properties,
                              CrashQualityMetrics metrics,
                              Clock clock) {
        if (properties.getMaxArtifactBytes() <= 0) {
            throw new IllegalStateException("apm.stack-parser.max-artifact-bytes 必须为正数");
        }
        if (properties.getMaxConcurrentParses() <= 0) {
            throw new IllegalStateException("apm.stack-parser.max-concurrent-parses 必须为正数");
        }
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.mappingResolver = mappingResolver;
        this.reportMapper = reportMapper;
        this.repository = repository;
        this.metrics = metrics;
        this.permits = new Semaphore(properties.getMaxConcurrentParses(), true);
        this.clock = clock;
    }

    public StackArtifactParseResponse parse(AuthenticatedApp app, InputStream artifactInput) {
        java.util.UUID appId = app.appId();
        metrics.jankReceived();
        if (!permits.tryAcquire()) {
            throw new StackParserBusyException();
        }
        try {
            AtomicBoolean mappingApplied = new AtomicBoolean();
            Path artifactFile = Files.createTempFile("apm-jank-artifact-", ".zip");
            try {
                Files.copy(artifactInput, artifactFile, StandardCopyOption.REPLACE_EXISTING);
                preflightV3Manifest(app, artifactFile);
                String reportJson;
                try (InputStream parserInput = Files.newInputStream(artifactFile)) {
                    reportJson = parser.parseWithMappingResolver(parserInput, metadata -> {
                        File mapping = mappingResolver.resolveOptional(appId, metadata.getMappingId());
                        mappingApplied.set(mapping != null);
                        return mapping;
                    });
                }
                JsonNode report = objectMapper.readTree(reportJson);
                validateAppPackage(app, report);
                StoredEvent event = reportMapper.map(appId, report, Instant.now(clock), mappingApplied.get());
                AppendResult result = repository.append(appId, List.of(event));
                if (result.accepted() == 1 && result.duplicate() == 0) {
                    metrics.jankAccepted(1);
                    recordSuccessMetrics(event);
                    return StackArtifactParseResponse.accepted();
                }
                if (result.accepted() == 0 && result.duplicate() == 1) {
                    metrics.jankDuplicate(1);
                    recordSuccessMetrics(event);
                    return StackArtifactParseResponse.duplicate();
                }
                throw new EventStoreUnavailableException("事件分析存储返回了无效写入结果");
            } finally {
                try {
                    Files.deleteIfExists(artifactFile);
                } catch (IOException ignored) {
                    // 临时文件清理失败不应覆盖原始解析结果。
                }
            }
        } catch (InvalidStackArtifactException | PayloadTooLargeException | PackageNameMismatchException
                 ex) {
            metrics.jankRejected();
            throw ex;
        } catch (EventStoreUnavailableException ex) {
            metrics.retryableFailure();
            throw ex;
        } catch (IOException | RuntimeException ex) {
            metrics.jankRejected();
            throw new InvalidStackArtifactException("INVALID_STACK_ARTIFACT", "卡顿产物无法解析", ex);
        } finally {
            permits.release();
        }
    }

    /**
     * 对真实 ZIP 的 manifest 做轻量预检，确保包名不匹配时不会触发 processor 或 mapping 读取。
     * 非 ZIP 输入继续交给 processor 产生稳定的产物解析错误，便于保留边界测试。
     */
    private void preflightV3Manifest(AuthenticatedApp app, Path artifactFile) {
        try (InputStream input = Files.newInputStream(artifactFile)) {
            byte[] signature = input.readNBytes(4);
            if (!isZipSignature(signature)) {
                return;
            }
        } catch (IOException ex) {
            throw new InvalidStackArtifactException("INVALID_STACK_ARTIFACT", "卡顿产物读取失败", ex);
        }

        JsonNode manifest = null;
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(artifactFile))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!"manifest.json".equals(entry.getName())) {
                    continue;
                }
                if (found) {
                    throw new InvalidStackArtifactException(
                            "INVALID_JANK_MANIFEST", "卡顿产物不得包含重复 manifest.json");
                }
                found = true;
                byte[] manifestBytes = readManifest(zip);
                try {
                    manifest = objectMapper.readTree(manifestBytes);
                } catch (RuntimeException ex) {
                    throw new InvalidStackArtifactException(
                            "INVALID_JANK_MANIFEST", "卡顿 manifest JSON 无法解析", ex);
                }
            }
        } catch (ZipException ex) {
            throw new InvalidStackArtifactException("INVALID_STACK_ARTIFACT", "卡顿产物不是有效 ZIP", ex);
        } catch (IOException ex) {
            throw new InvalidStackArtifactException("INVALID_STACK_ARTIFACT", "卡顿产物读取失败", ex);
        }

        if (!found) {
            throw new InvalidStackArtifactException(
                    "INVALID_JANK_MANIFEST", "卡顿产物缺少 manifest.json");
        }
        if (manifest == null || !manifest.isObject()) {
            throw new InvalidStackArtifactException(
                    "INVALID_JANK_MANIFEST", "卡顿 manifest 必须是 JSON 对象");
        }
        if (manifest.has("appId")) {
            throw new InvalidStackArtifactException(
                    "INVALID_JANK_MANIFEST", "卡顿 manifest 不得使用旧 appId 字段");
        }
        if (!manifest.path("schemaVersion").isIntegralNumber()
                || manifest.path("schemaVersion").asInt() != 3
                || !manifest.path("artifactType").isTextual()
                || !"RHEA_JANK".equals(manifest.path("artifactType").asText())) {
            throw new InvalidStackArtifactException(
                    "UNSUPPORTED_JANK_ARTIFACT", "只支持 v3 RHEA_JANK 产物");
        }
        JsonNode packageName = manifest.get("packageName");
        if (packageName == null || !packageName.isTextual() || packageName.asText().isBlank()) {
            throw new InvalidStackArtifactException(
                    "INVALID_JANK_MANIFEST", "卡顿 manifest 缺少有效 packageName");
        }
        if (!app.packageName().equals(packageName.asText())) {
            throw new PackageNameMismatchException();
        }
    }

    private byte[] readManifest(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
            if (count == 0) {
                continue;
            }
            total += count;
            if (total > MAX_MANIFEST_BYTES) {
                throw new InvalidStackArtifactException(
                        "JANK_EVIDENCE_LIMIT_EXCEEDED", "卡顿 manifest 超过大小上限");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private boolean isZipSignature(byte[] signature) {
        return signature.length >= 4
                && signature[0] == 'P'
                && signature[1] == 'K'
                && ((signature[2] == 3 && signature[3] == 4)
                || (signature[2] == 5 && signature[3] == 6)
                || (signature[2] == 7 && signature[3] == 8));
    }

    private void validateAppPackage(AuthenticatedApp app, JsonNode report) {
        String packageName = reportMapper.sourcePackageName(report);
        if (!app.packageName().equals(packageName)) {
            throw new PackageNameMismatchException();
        }
    }

    private void recordSuccessMetrics(StoredEvent event) {
        metrics.jankSchemaVersion(event.schemaVersion());
        metrics.jankAlgorithmVersion(event.eventType(), event.jank().algorithmVersion());
        java.time.Duration delay = java.time.Duration.between(event.occurredAt(), event.receivedAt());
        if (!delay.isNegative()) {
            metrics.jankVisibleDelay(delay);
        }
    }
}
