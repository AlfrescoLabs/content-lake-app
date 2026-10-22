package org.hyland.contentlake.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The upstream repositories rag-service authenticates callers against, resolves groups in and links to.
 *
 * <p>One entry per repository, so two repositories of one type (two Alfresco installations, say) are two
 * entries, each with its own URL and service account. Bound from {@code rag.sources[]}. When that list is
 * empty, the entries are built from the per-type keys every deployment already sets
 * ({@code content.service.*} and {@code alfresco.source-id} for Alfresco, {@code nuxeo.*} for Nuxeo), so a
 * deployment that sets none of the new keys behaves exactly as before.</p>
 *
 * <p>An entry's {@code id} is the bare source id, the part of {@code cin_sourceId} after
 * {@code <type>:}. It may be left empty while a type has a single entry, which keeps the id discovered from
 * the index. Two entries of one type must both name their id: nothing else tells their documents apart.</p>
 */
@Slf4j
@Component
public class SourceRegistry {

    public static final String ALFRESCO = "alfresco";
    public static final String NUXEO = "nuxeo";

    /** One upstream repository. {@code id} is bare and may be null for the only entry of its type. */
    public record Source(String id, String type, String url, String serviceUsername, String servicePassword,
                         String linkTemplate, String displayName) {

        /** {@code <type>:<id>} as stored in {@code cin_sourceId}, or null when the id is not configured. */
        public String qualifiedId() {
            return id == null ? null : type + ":" + id;
        }

        /** What a login screen shows for this source. */
        public String label() {
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            String typeLabel = Character.toUpperCase(type.charAt(0)) + type.substring(1);
            return id == null ? typeLabel : typeLabel + " (" + id + ")";
        }
    }

    private final List<Source> sources;

    @Autowired
    public SourceRegistry(RagProperties ragProperties,
                          @Value("${content.service.url:}") String alfrescoUrl,
                          @Value("${content.service.security.basicAuth.username:}") String alfrescoUsername,
                          @Value("${content.service.security.basicAuth.password:}") String alfrescoPassword,
                          @Value("${alfresco.source-id:}") String alfrescoSourceId,
                          @Value("${nuxeo.base-url:}") String nuxeoUrl,
                          @Value("${nuxeo.username:}") String nuxeoUsername,
                          @Value("${nuxeo.password:}") String nuxeoPassword,
                          @Value("${nuxeo.source-id:}") String nuxeoSourceId) {
        List<RagProperties.SourceProperties> configured = ragProperties.getSources();
        this.sources = configured == null || configured.isEmpty()
                ? legacy(alfrescoUrl, alfrescoUsername, alfrescoPassword, alfrescoSourceId,
                         nuxeoUrl, nuxeoUsername, nuxeoPassword, nuxeoSourceId)
                : fromProperties(configured);
        validate(sources);
        log.info("Sources: {}", sources.stream()
                .map(s -> s.type() + ":" + (s.id() == null ? "<discovered>" : s.id()) + "@" + s.url())
                .toList());
    }

    /** A registry over the given entries, for tests. */
    public static SourceRegistry of(List<Source> sources) {
        return new SourceRegistry(sources);
    }

    private SourceRegistry(List<Source> sources) {
        validate(sources);
        this.sources = List.copyOf(sources);
    }

    /** Every entry, in configuration order. */
    public List<Source> all() {
        return sources;
    }

    /** The entries of one type, in configuration order. */
    public List<Source> ofType(String type) {
        String normalized = normalizeType(type);
        return sources.stream().filter(s -> s.type().equals(normalized)).toList();
    }

    /** The entry with this bare or qualified id. */
    public Optional<Source> byId(String sourceId) {
        String bare = bareId(sourceId);
        return bare == null
                ? Optional.empty()
                : sources.stream().filter(s -> bare.equals(s.id())).findFirst();
    }

    /**
     * The entry that answers for a source: the one configured with its id, else the only entry of its type.
     * Empty when the type has several entries and none names this id, which callers treat as "no directory
     * for this source" rather than guessing one.
     */
    public Optional<Source> forSource(String sourceId, String sourceType) {
        Optional<Source> exact = byId(sourceId);
        if (exact.isPresent()) {
            return exact;
        }
        List<Source> ofType = ofType(sourceType);
        return ofType.size() == 1 && ofType.get(0).id() == null ? Optional.of(ofType.get(0)) : Optional.empty();
    }

    /** Configured bare ids per type, for the permission source catalogue. Types without ids are absent. */
    public Map<String, List<String>> configuredIdsByType() {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (Source source : sources) {
            if (source.id() != null) {
                byType.computeIfAbsent(source.type(), t -> new ArrayList<>()).add(source.id());
            }
        }
        byType.replaceAll((type, ids) -> List.copyOf(ids));
        return Collections.unmodifiableMap(byType);
    }

    private static List<Source> legacy(String alfrescoUrl, String alfrescoUsername, String alfrescoPassword,
                                       String alfrescoSourceId, String nuxeoUrl, String nuxeoUsername,
                                       String nuxeoPassword, String nuxeoSourceId) {
        List<Source> legacy = new ArrayList<>();
        if (!isBlank(alfrescoUrl)) {
            legacy.add(new Source(bareId(alfrescoSourceId), ALFRESCO, alfrescoUrl, alfrescoUsername,
                    alfrescoPassword, null, null));
        }
        if (!isBlank(nuxeoUrl)) {
            legacy.add(new Source(bareId(nuxeoSourceId), NUXEO, nuxeoUrl, nuxeoUsername, nuxeoPassword, null,
                    null));
        }
        return List.copyOf(legacy);
    }

    private static List<Source> fromProperties(List<RagProperties.SourceProperties> configured) {
        List<Source> entries = new ArrayList<>();
        for (RagProperties.SourceProperties p : configured) {
            entries.add(new Source(bareId(p.getId()), normalizeType(p.getType()), trimToNull(p.getUrl()),
                    p.getServiceUsername(), p.getServicePassword(), trimToNull(p.getLinkTemplate()),
                    trimToNull(p.getDisplayName())));
        }
        return List.copyOf(entries);
    }

    private static void validate(List<Source> sources) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, Integer> perType = new LinkedHashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            if (s.type() == null) {
                throw new IllegalStateException("rag.sources[" + i + "] has no type");
            }
            if (s.url() == null) {
                throw new IllegalStateException("rag.sources[" + i + "] (" + s.type() + ") has no url");
            }
            if (s.id() != null && !ids.add(s.id())) {
                // The bare id is the sys_racl namespace, so it must be unique across types, not only within one.
                throw new IllegalStateException("Source id '" + s.id() + "' is configured twice in rag.sources");
            }
            perType.merge(s.type(), 1, Integer::sum);
        }
        for (Source s : sources) {
            if (s.id() == null && perType.get(s.type()) > 1) {
                throw new IllegalStateException("rag.sources has " + perType.get(s.type()) + " entries of type '"
                        + s.type() + "', so every one of them must set id; " + s.url() + " does not");
            }
        }
    }

    /** The source id without its type prefix, or null when blank. */
    public static String bareId(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();
        int separator = trimmed.indexOf(':');
        return separator >= 0 && separator < trimmed.length() - 1 ? trimmed.substring(separator + 1) : trimmed;
    }

    private static String normalizeType(String type) {
        return isBlank(type) ? null : type.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
