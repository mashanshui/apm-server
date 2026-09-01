package com.shanshui.apmserver;

import com.shanshui.apmserver.config.StackParserProperties;
import com.shanshui.apmserver.service.InvalidStackArtifactException;
import com.shanshui.apmserver.service.AppStackMappingResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private AppStackMappingResolver resolver() {
        StackParserProperties properties = new StackParserProperties();
        properties.setMappingRoot(mappingRoot.toString());
        return new AppStackMappingResolver(properties);
    }
}
