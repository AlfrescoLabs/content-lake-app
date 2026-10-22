package org.hyland.nuxeo.contentlake.live.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.hyland.nuxeo.contentlake.live.model.AuditCursor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Keeps audit cursors in a JSON file, one file per source.
 *
 * <p>Each write goes to a temporary file that is then renamed over the cursor file, so a crash leaves
 * either the old position or the new one, never a half-written file. The file is still rewritten whole
 * on each save, which is why two ingesters must never share one: each would overwrite the other's key.
 * {@link #defaultCursorFile(Path, String)} names the file after the source id for that reason.</p>
 *
 * <p>A legacy file, the single shared {@code audit-cursor.json}, can be given as a fallback. The first
 * load of a key this store does not hold reads it once and copies that key's cursor into this store's own
 * file, so an upgrade resumes where it was instead of replaying the audit log. The legacy file is left
 * as it is, since another source's ingester may still need its own key from it.</p>
 */
@Slf4j
public class FileAuditCursorStore implements AuditCursorStore {

    /** The name of the single cursor file all sources shared. */
    public static final String LEGACY_FILE_NAME = "audit-cursor.json";

    /**
     * The cursor file's own mapper, owned here rather than injected.
     *
     * <p>{@link AuditCursor#lastLogDate()} is an {@link java.time.OffsetDateTime}, which Jackson
     * serialises only with {@link JavaTimeModule} registered. Taking the mapper from the application
     * context made that a property of whichever bean happened to be injected: the shared Jackson 2
     * bean carries no modules, and Jackson's {@code REQUIRE_HANDLERS_FOR_JAVA8_TIMES} turns the gap
     * into a hard failure, so every cycle with a cursor to save died and the position never advanced.
     * Owning the mapper makes the format a property of this class, and the tests exercise the same
     * one production uses.</p>
     *
     * <p>Timestamps are written as ISO-8601 strings rather than epoch decimals so the file can be read
     * by an operator diagnosing where the ingester resumed from.</p>
     */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final Path cursorFile;
    private final Path legacyFile;
    private boolean legacyRead;

    public FileAuditCursorStore(Path cursorFile) {
        this(cursorFile, null);
    }

    /**
     * @param cursorFile this store's own file
     * @param legacyFile a shared cursor file to read once for keys this store does not hold yet, or
     *                   {@code null} for none
     */
    public FileAuditCursorStore(Path cursorFile, Path legacyFile) {
        this.cursorFile = cursorFile;
        this.legacyFile = legacyFile == null || legacyFile.equals(cursorFile) ? null : legacyFile;
    }

    /**
     * {@code <directory>/audit-cursor-<sourceId>.json}. Characters that are unsafe in a file name are
     * replaced with {@code _}.
     */
    public static Path defaultCursorFile(Path directory, String sourceId) {
        String safeId = sourceId == null || sourceId.isBlank()
                ? "default"
                : sourceId.replaceAll("[^A-Za-z0-9._-]", "_");
        return directory.resolve("audit-cursor-" + safeId + ".json");
    }

    public Path cursorFile() {
        return cursorFile;
    }

    @Override
    public synchronized Optional<AuditCursor> load(String repositoryKey) {
        CursorState state = readState(cursorFile);
        AuditCursor cursor = state.cursors().get(repositoryKey);
        if (cursor != null) {
            return Optional.of(cursor);
        }
        return loadFromLegacy(repositoryKey, state);
    }

    private Optional<AuditCursor> loadFromLegacy(String repositoryKey, CursorState state) {
        if (legacyFile == null || legacyRead) {
            return Optional.empty();
        }
        legacyRead = true;
        AuditCursor legacyCursor = readState(legacyFile).cursors().get(repositoryKey);
        if (legacyCursor == null) {
            return Optional.empty();
        }
        Map<String, AuditCursor> updated = new LinkedHashMap<>(state.cursors());
        updated.put(repositoryKey, legacyCursor);
        writeState(new CursorState(updated));
        log.info("Copied the audit cursor for {} from {} to {}; the old file is no longer read for it",
                repositoryKey, legacyFile, cursorFile);
        return Optional.of(legacyCursor);
    }

    @Override
    public synchronized void save(String repositoryKey, AuditCursor cursor) {
        CursorState state = readState(cursorFile);
        Map<String, AuditCursor> updated = new LinkedHashMap<>(state.cursors());
        updated.put(repositoryKey, cursor);
        writeState(new CursorState(updated));
    }

    private static CursorState readState(Path file) {
        if (Files.notExists(file)) {
            return CursorState.empty();
        }
        try {
            CursorState state = OBJECT_MAPPER.readValue(file.toFile(), CursorState.class);
            return state != null ? state : CursorState.empty();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read audit cursor file " + file, e);
        }
    }

    private void writeState(CursorState state) {
        try {
            Path parent = cursorFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = cursorFile.resolveSibling(cursorFile.getFileName() + ".tmp");
            OBJECT_MAPPER.writeValue(tempFile.toFile(), new CursorState(new TreeMap<>(state.cursors())));
            try {
                Files.move(tempFile, cursorFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, cursorFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write audit cursor file " + cursorFile, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CursorState(Map<String, AuditCursor> cursors) {

        private CursorState {
            cursors = cursors == null ? Map.of() : Map.copyOf(cursors);
        }

        static CursorState empty() {
            return new CursorState(Map.of());
        }
    }
}
