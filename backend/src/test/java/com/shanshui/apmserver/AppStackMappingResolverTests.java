package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.config.StackParserProperties;
import com.shanshui.apmserver.jank.api.InvalidStackArtifactException;
import com.shanshui.apmserver.jank.internal.artifact.AppStackMappingResolver;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AppStackMappingResolverTests {

    @TempDir
    Path mappingRoot;

    @Test
    void resolvesOnlyMappingInsideCurrentAppDirectory() throws Exception {
        UUID appId = TestAppIds.id("demo-app");
        Path appDirectory = Files.createDirectories(mappingRoot.resolve(appId.toString()));
        Path mapping = Files.writeString(appDirectory.resolve("build-320.txt"),
                "com.example.a -> a:");
        AppStackMappingResolver resolver = resolver();

        assertEquals(mapping.toFile(), resolver.resolveOptional(appId, "build-320"));
        assertNull(resolver.resolveOptional(TestAppIds.id("other-app"), "build-320"));
    }

    @Test
    void rejectsPathLikeOrBlankBuildIdBeforeFilesystemLookup() {
        AppStackMappingResolver resolver = resolver();

        InvalidStackArtifactException exception = assertThrows(
                InvalidStackArtifactException.class,
                () -> resolver.resolveOptional(TestAppIds.id("demo-app"), "../secret"));

        assertEquals("INVALID_BUILD_ID", exception.getCode());

        InvalidStackArtifactException blank = assertThrows(
                InvalidStackArtifactException.class,
                () -> resolver.resolveOptional(TestAppIds.id("demo-app"), " "));
        assertEquals("INVALID_BUILD_ID", blank.getCode());
    }

    @Test
    void missingAndCrossAppMappingsRemainOptional() throws Exception {
        UUID appA = TestAppIds.id("app-a");
        Files.createDirectories(mappingRoot.resolve(appA.toString()));
        Files.writeString(Files.createDirectories(mappingRoot.resolve(TestAppIds.id("app-b").toString()))
                .resolve("shared.txt"), "mapping");
        AppStackMappingResolver resolver = resolver();

        assertNull(resolver.resolveOptional(appA, "missing"));
        assertNull(resolver.resolveOptional(appA, "shared"));
    }

    @Test
    void rejectsSymbolicLinkEscapeWhenPlatformSupportsIt() throws Exception {
        Path shared = Files.writeString(Files.createDirectories(mappingRoot.resolve("shared")).resolve("build.txt"),
                "mapping");
        UUID appId = TestAppIds.id("demo-app");
        Path app = Files.createDirectories(mappingRoot.resolve(appId.toString()));
        Path mappingLink = app.resolve("build.txt");
        try {
            Files.createSymbolicLink(mappingLink, shared);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            assumeTrue(false, "当前平台不允许创建符号链接: " + ex.getClass().getSimpleName());
        }

        InvalidStackArtifactException exception = assertThrows(InvalidStackArtifactException.class,
                () -> resolver().resolveOptional(appId, "build"));
        assertEquals("INVALID_MAPPING_PATH", exception.getCode());
    }

    @Test
    void resolvesThroughSharedRegistryAndKeepsLeaseUntilParserReleasesIt() throws Exception {
        UUID appId = TestAppIds.id("demo-app");
        Path mapping = Files.writeString(mappingRoot.resolve("registered.mapping"), "com.example.a -> a:");
        AtomicBoolean closed = new AtomicBoolean();
        SymbolFileLease lease = new SymbolFileLease() {
            @Override
            public UUID symbolId() {
                return TestAppIds.id("symbol");
            }

            @Override
            public int revision() {
                return 3;
            }

            /** 合成租约摘要，不用于真实 mapping 验收。 */
            @Override
            public String sha256() { return "a".repeat(64); }

            @Override
            public Path path() {
                return mapping;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        SymbolRegistry registry = new SymbolRegistry() {
            @Override
            public Optional<SymbolFileLease> acquire(UUID requestedAppId, String buildId) {
                assertEquals(appId, requestedAppId);
                assertEquals("build-320", buildId);
                return Optional.of(lease);
            }

            @Override
            public SymbolicationResult retrace(SymbolFileLease ignored, List<String> stackLines) {
                return SymbolicationResult.failed("unused");
            }
        };

        AppStackMappingResolver resolver = new AppStackMappingResolver(registry);
        AppStackMappingResolver.ResolvedMapping resolved = resolver.resolve(appId, "build-320");

        assertEquals(mapping.toFile(), resolved.file());
        assertEquals(lease, resolved.lease());
        assertTrue(!closed.get());
        resolved.lease().close();
        assertTrue(closed.get());
    }

    @Test
    void propagatesRegistryUnavailableAsRetryableFailure() {
        SymbolRegistry registry = new SymbolRegistry() {
            @Override
            public java.util.Optional<SymbolFileLease> acquire(UUID appId, String buildId) {
                throw new SymbolStoreUnavailableException("registry unavailable");
            }

            @Override
            public SymbolicationResult retrace(SymbolFileLease lease, List<String> stackLines) {
                return SymbolicationResult.failed("unused");
            }
        };

        assertThrows(SymbolStoreUnavailableException.class,
                () -> new AppStackMappingResolver(registry).resolve(TestAppIds.id("demo-app"), "build-320"));
    }

    private AppStackMappingResolver resolver() {
        StackParserProperties properties = new StackParserProperties();
        properties.setMappingRoot(mappingRoot.toString());
        return new AppStackMappingResolver(properties);
    }
}
