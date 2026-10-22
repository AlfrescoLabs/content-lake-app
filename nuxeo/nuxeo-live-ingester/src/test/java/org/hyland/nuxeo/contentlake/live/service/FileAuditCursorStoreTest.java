package org.hyland.nuxeo.contentlake.live.service;

import org.hyland.nuxeo.contentlake.live.model.AuditCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FileAuditCursorStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoad_roundTripsPerRepositoryKey() {
        FileAuditCursorStore store = new FileAuditCursorStore(tempDir.resolve("audit-cursor.json"));

        AuditCursor first = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:48:41.235Z"), 46);
        AuditCursor second = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:48:45.939Z"), 48);

        store.save("nuxeo:local", first);
        store.save("nuxeo:other", second);

        assertThat(store.load("nuxeo:local")).contains(first);
        assertThat(store.load("nuxeo:other")).contains(second);
        assertThat(store.load("missing")).isEmpty();
    }

    /**
     * The regression #126 was: the store took its mapper from the application context, the shared
     * Jackson 2 bean carried no {@code JavaTimeModule}, and every save of an {@code OffsetDateTime}
     * cursor threw, so the position never advanced. The store now owns its mapper, so a caller cannot
     * hand it one that cannot write the cursor -- this test asserts that a save with no mapper
     * configuration whatsoever succeeds and lands on disk.
     */
    @Test
    void save_writesTheCursorWithoutMapperConfigurationFromTheCaller() throws Exception {
        Path cursorFile = tempDir.resolve("audit-cursor.json");
        FileAuditCursorStore store = new FileAuditCursorStore(cursorFile);

        assertThatCode(() -> store.save("nuxeo:local",
                new AuditCursor(OffsetDateTime.parse("2026-03-26T16:48:41.235Z"), 46)))
                .doesNotThrowAnyException();

        assertThat(cursorFile).exists();
        // ISO-8601 rather than an epoch decimal, so the file is readable by whoever is diagnosing a
        // resume position.
        assertThat(Files.readString(cursorFile)).contains("2026-03-26T16:48:41.235Z");
    }

    /** A restart is a fresh store over the same file, which must resume from what was written. */
    @Test
    void aCursorSurvivesARestart() {
        Path cursorFile = tempDir.resolve("audit-cursor.json");
        AuditCursor cursor = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:48:41.235Z"), 46);

        new FileAuditCursorStore(cursorFile).save("nuxeo:local", cursor);

        assertThat(new FileAuditCursorStore(cursorFile).load("nuxeo:local")).contains(cursor);
    }

    /**
     * A cursor file written before the ISO-8601 switch holds an epoch decimal. Reading one must still
     * work, or an upgrade would fail on the first load rather than merely re-reading from the start.
     */
    @Test
    void anEpochTimestampWrittenByAnEarlierBuildStillLoads() throws Exception {
        Path cursorFile = tempDir.resolve("audit-cursor.json");
        Files.writeString(cursorFile,
                "{\"cursors\":{\"nuxeo:local\":{\"lastLogDate\":1774543721.235000000,\"lastEntryId\":46}}}");

        assertThat(new FileAuditCursorStore(cursorFile).load("nuxeo:local"))
                .map(AuditCursor::lastEntryId)
                .contains(46L);
    }

    /** Two live ingesters on one volume, each with its own source id and so its own file. */
    @Test
    void twoStoresWithDifferentSourceIdsInOneDirectoryKeepIndependentCursors() {
        FileAuditCursorStore storeA = new FileAuditCursorStore(FileAuditCursorStore.defaultCursorFile(tempDir, "a"));
        FileAuditCursorStore storeB = new FileAuditCursorStore(FileAuditCursorStore.defaultCursorFile(tempDir, "b"));
        AuditCursor a1 = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:00:00Z"), 1);
        AuditCursor b1 = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:01:00Z"), 2);
        AuditCursor a2 = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:02:00Z"), 3);

        storeA.save("nuxeo:a", a1);
        storeB.save("nuxeo:b", b1);
        storeA.save("nuxeo:a", a2);

        assertThat(storeA.cursorFile()).isNotEqualTo(storeB.cursorFile());
        assertThat(new FileAuditCursorStore(storeA.cursorFile()).load("nuxeo:a")).contains(a2);
        assertThat(new FileAuditCursorStore(storeB.cursorFile()).load("nuxeo:b")).contains(b1);
        assertThat(new FileAuditCursorStore(storeA.cursorFile()).load("nuxeo:b")).isEmpty();
    }

    @Test
    void defaultCursorFile_isNamedAfterTheSourceId() {
        assertThat(FileAuditCursorStore.defaultCursorFile(tempDir, "prod-1"))
                .isEqualTo(tempDir.resolve("audit-cursor-prod-1.json"));
        assertThat(FileAuditCursorStore.defaultCursorFile(tempDir, "../x y"))
                .isEqualTo(tempDir.resolve("audit-cursor-.._x_y.json"));
    }

    /** The write goes through a temporary file that is renamed into place, so none is left behind. */
    @Test
    void save_leavesNoTemporaryFile() throws Exception {
        Path cursorFile = tempDir.resolve("audit-cursor-a.json");

        new FileAuditCursorStore(cursorFile).save("nuxeo:a",
                new AuditCursor(OffsetDateTime.parse("2026-03-26T16:00:00Z"), 1));

        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("audit-cursor-a.json");
        }
    }

    /** An upgrade from the shared file resumes where it was instead of replaying the audit log. */
    @Test
    void aCursorInTheLegacySharedFileIsCarriedOver() throws Exception {
        Path legacyFile = tempDir.resolve(FileAuditCursorStore.LEGACY_FILE_NAME);
        AuditCursor legacyA = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:00:00Z"), 7);
        AuditCursor legacyB = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:05:00Z"), 9);
        FileAuditCursorStore legacy = new FileAuditCursorStore(legacyFile);
        legacy.save("nuxeo:a", legacyA);
        legacy.save("nuxeo:b", legacyB);
        String legacyContent = Files.readString(legacyFile);

        Path ownFile = FileAuditCursorStore.defaultCursorFile(tempDir, "a");
        assertThat(new FileAuditCursorStore(ownFile, legacyFile).load("nuxeo:a")).contains(legacyA);

        // Copied into the store's own file, so the next start does not depend on the legacy file.
        assertThat(new FileAuditCursorStore(ownFile).load("nuxeo:a")).contains(legacyA);
        assertThat(new FileAuditCursorStore(ownFile).load("nuxeo:b")).isEmpty();
        // Left untouched for the other source's ingester.
        assertThat(Files.readString(legacyFile)).isEqualTo(legacyContent);
        assertThat(new FileAuditCursorStore(FileAuditCursorStore.defaultCursorFile(tempDir, "b"), legacyFile)
                .load("nuxeo:b")).contains(legacyB);
    }

    @Test
    void theLegacyFileIsReadOnlyOnce() {
        Path legacyFile = tempDir.resolve(FileAuditCursorStore.LEGACY_FILE_NAME);
        FileAuditCursorStore store =
                new FileAuditCursorStore(FileAuditCursorStore.defaultCursorFile(tempDir, "a"), legacyFile);

        assertThat(store.load("nuxeo:a")).isEmpty();
        new FileAuditCursorStore(legacyFile).save("nuxeo:a",
                new AuditCursor(OffsetDateTime.parse("2026-03-26T16:00:00Z"), 7));

        assertThat(store.load("nuxeo:a")).isEmpty();
    }

    /** A saved position is never replaced by an older one from the legacy file. */
    @Test
    void theStoresOwnCursorWinsOverTheLegacyFile() {
        Path legacyFile = tempDir.resolve(FileAuditCursorStore.LEGACY_FILE_NAME);
        new FileAuditCursorStore(legacyFile).save("nuxeo:a",
                new AuditCursor(OffsetDateTime.parse("2026-03-26T16:00:00Z"), 7));
        Path ownFile = FileAuditCursorStore.defaultCursorFile(tempDir, "a");
        AuditCursor own = new AuditCursor(OffsetDateTime.parse("2026-03-26T17:00:00Z"), 70);
        new FileAuditCursorStore(ownFile).save("nuxeo:a", own);

        assertThat(new FileAuditCursorStore(ownFile, legacyFile).load("nuxeo:a")).contains(own);
    }
}
