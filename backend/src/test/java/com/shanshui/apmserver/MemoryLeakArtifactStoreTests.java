package com.shanshui.apmserver;

import com.shanshui.apmserver.memory.internal.application.MemoryLeakArtifactCleanup;
import com.shanshui.apmserver.memory.internal.application.MemoryLeakArtifactStore;
import com.shanshui.apmserver.memory.internal.config.MemoryLeakReportProperties;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport;
import com.shanshui.apmserver.memory.internal.port.MemoryLeakReportRepository;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证附件受限落盘、过期清理和存储不可用时的保护边界。 */
class MemoryLeakArtifactStoreTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesServerNamedFileAndRejectsOversizedStream() throws Exception {
        MemoryLeakReportProperties config = config(temporaryDirectory, 3);
        MemoryLeakArtifactStore store = new MemoryLeakArtifactStore(config);
        MemoryLeakArtifactStore.StoredArtifact stored = store.store(UUID.randomUUID(), UUID.randomUUID(),
                new ByteArrayInputStream(new byte[]{1, 2, 3}));

        Path path = Path.of(stored.path());
        assertThat(path.getParent()).isEqualTo(temporaryDirectory.toAbsolutePath().normalize());
        assertThat(path.getFileName().toString()).endsWith(".hprof");
        assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 3);
        assertThat(store.digest(new ByteArrayInputStream(new byte[]{1, 2, 3}))).isEqualTo(stored.digest());

        assertThatThrownBy(() -> store.store(UUID.randomUUID(), UUID.randomUUID(),
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4})))
                .isInstanceOf(com.shanshui.apmserver.platform.api.PayloadTooLargeException.class);
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.toList()).hasSize(1);
        }
    }

    @Test
    void reportsAttachmentStoreFailureWhenRootIsNotADirectory() throws Exception {
        Path rootFile = temporaryDirectory.resolve("artifact-root");
        Files.write(rootFile, new byte[]{1});
        MemoryLeakReportProperties config = config(rootFile, 100);
        MemoryLeakArtifactStore store = new MemoryLeakArtifactStore(config);

        assertThatThrownBy(() -> store.store(UUID.randomUUID(), UUID.randomUUID(),
                new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(com.shanshui.apmserver.memory.api.MemoryLeakAttachmentStoreException.class);
    }

    @Test
    void cleansOnlyExpiredOrOrphanedFilesAndKeepsRecentFiles() throws Exception {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        MemoryLeakArtifactStore store = new MemoryLeakArtifactStore(config(temporaryDirectory, 100));
        MemoryLeakArtifactStore.StoredArtifact oldReferenced = store.store(UUID.randomUUID(), UUID.randomUUID(),
                new ByteArrayInputStream(new byte[]{1}));
        MemoryLeakArtifactStore.StoredArtifact recentReferenced = store.store(UUID.randomUUID(), UUID.randomUUID(),
                new ByteArrayInputStream(new byte[]{2}));
        Path orphanOld = temporaryDirectory.resolve("orphan-old.hprof");
        Path orphanRecent = temporaryDirectory.resolve("orphan-recent.hprof");
        Path temporaryOld = temporaryDirectory.resolve("upload-old.tmp");
        Path temporaryRecent = temporaryDirectory.resolve("upload-recent.tmp");
        Files.write(orphanOld, new byte[]{3}); Files.write(orphanRecent, new byte[]{4});
        Files.write(temporaryOld, new byte[]{5}); Files.write(temporaryRecent, new byte[]{6});
        FileTime old = FileTime.from(now.minus(Duration.ofDays(8)));
        FileTime orphanOldTime = FileTime.from(now.minus(Duration.ofDays(2)));
        FileTime recent = FileTime.from(now.minus(Duration.ofHours(1)));
        Files.setLastModifiedTime(Path.of(oldReferenced.path()), old);
        Files.setLastModifiedTime(Path.of(recentReferenced.path()), recent);
        Files.setLastModifiedTime(orphanOld, orphanOldTime); Files.setLastModifiedTime(orphanRecent, recent);
        Files.setLastModifiedTime(temporaryOld, orphanOldTime); Files.setLastModifiedTime(temporaryRecent, recent);

        MemoryLeakArtifactStore.CleanupResult result = store.cleanup(Set.of(oldReferenced.path(), recentReferenced.path()), now);

        assertThat(result.attachmentDeleted()).isEqualTo(2);
        assertThat(result.temporaryDeleted()).isEqualTo(1);
        assertThat(Files.exists(Path.of(oldReferenced.path()))).isFalse();
        assertThat(Files.exists(orphanOld)).isFalse();
        assertThat(Files.exists(Path.of(recentReferenced.path()))).isTrue();
        assertThat(Files.exists(orphanRecent)).isTrue();
        assertThat(Files.exists(temporaryRecent)).isTrue();
    }

    @Test
    void skipsCleanupWhenReportStorageIsUnavailable() throws Exception {
        MemoryLeakReportProperties config = config(temporaryDirectory, 100);
        Path orphan = temporaryDirectory.resolve("orphan.hprof");
        Files.write(orphan, new byte[]{1});
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        new MemoryLeakArtifactCleanup(new UnavailableRepository(), new MemoryLeakArtifactStore(config)).cleanup();

        assertThat(Files.exists(orphan)).isTrue();
    }

    private MemoryLeakReportProperties config(Path directory, long maxAttachmentBytes) {
        MemoryLeakReportProperties config = new MemoryLeakReportProperties();
        config.setArtifactDirectory(directory.toString());
        config.setMaxAttachmentBytes(maxAttachmentBytes);
        config.setAttachmentRetentionDays(7);
        config.setOrphanGraceHours(24);
        return config;
    }

    private static final class UnavailableRepository implements MemoryLeakReportRepository {
        @Override public Optional<MemoryLeakReport> findByEventId(UUID appId, UUID eventId) { throw unavailable(); }
        @Override public void append(MemoryLeakReport report) { throw unavailable(); }
        @Override public List<MemoryLeakReport> findAll(MemoryLeakQueryFilter filter) { throw unavailable(); }
        @Override public com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse issues(MemoryLeakQueryFilter filter,int page,int size,String sort,String order) { throw unavailable(); }
        @Override public List<com.shanshui.apmserver.memory.api.MemoryLeakTrendPoint> trend(MemoryLeakQueryFilter filter,long seconds) { throw unavailable(); }
        @Override public Set<String> findAttachmentPaths() { throw unavailable(); }
        @Override public String dataSource() { return "test"; }
        private EventStoreUnavailableException unavailable() { return new EventStoreUnavailableException(); }
    }
}
