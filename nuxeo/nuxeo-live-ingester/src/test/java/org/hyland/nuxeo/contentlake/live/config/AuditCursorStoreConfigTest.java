package org.hyland.nuxeo.contentlake.live.config;

import org.hyland.nuxeo.contentlake.config.NuxeoProperties;
import org.hyland.nuxeo.contentlake.live.model.AuditCursor;
import org.hyland.nuxeo.contentlake.live.service.FileAuditCursorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Which cursor file the live ingester uses, and that an upgrade picks up the shared one. */
class AuditCursorStoreConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void withoutAnExplicitFile_theCursorFileIsNamedAfterTheSourceId() {
        FileAuditCursorStore store = store("", "prod-nuxeo");

        assertThat(store.cursorFile()).isEqualTo(tempDir.resolve("audit-cursor-prod-nuxeo.json"));
    }

    @Test
    void anExplicitFileIsUsedAsGiven() {
        Path explicit = tempDir.resolve("custom.json");

        assertThat(store(explicit.toString(), "prod-nuxeo").cursorFile()).isEqualTo(explicit);
    }

    @Test
    void theSharedCursorFileInTheDirectoryIsReadOnUpgrade() {
        AuditCursor legacy = new AuditCursor(OffsetDateTime.parse("2026-03-26T16:00:00Z"), 7);
        new FileAuditCursorStore(tempDir.resolve(FileAuditCursorStore.LEGACY_FILE_NAME)).save("nuxeo:local", legacy);

        assertThat(store("", "local").load("nuxeo:local")).contains(legacy);
    }

    private FileAuditCursorStore store(String cursorFile, String sourceId) {
        NuxeoLiveProperties props = new NuxeoLiveProperties();
        props.getAudit().setCursorFile(cursorFile);
        props.getAudit().setCursorDir(tempDir.toString());
        NuxeoProperties nuxeoProps = new NuxeoProperties();
        nuxeoProps.setSourceId(sourceId);
        return (FileAuditCursorStore) new AppConfig().auditCursorStore(props, nuxeoProps);
    }
}
