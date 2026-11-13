package org.hyland.contentlake.rag.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.hyland.contentlake.security.SourceGroupResolver;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Group membership from any directory that answers over HTTP with JSON, configured per {@code rag.sources[]}
 * entry rather than written per source type (#192).
 *
 * <p>A connector plugin can ingest group ACLs, but before this the only resolvers were the built-in Alfresco
 * and Nuxeo ones and the conditional Entra one, so a plugin source's group-granted documents were readable by
 * nobody until a resolver for its type was added to this service. This resolver keeps the two rules that
 * shape the query side: it is configuration in {@code rag-service}, not code shipped in a plugin jar, and
 * the connector SPI stays the ingest contract only.</p>
 *
 * <p>The request is {@code GET <url><path>} with {@code {username}} replaced by the URL-encoded caller name,
 * the entry's service account as Basic auth when one is set, and the configured headers. The answers follow
 * {@link SourceGroupResolver}: a 2xx with an array at the configured pointer is the caller's groups (an empty
 * array included); a 404 is the directory holding no such identity, so {@code null}; anything else, a
 * timeout, a body that is not JSON, or a pointer that does not reach an array, is a failure and throws, so
 * {@code rag.security.group-resolution-failure} decides what it costs. A malformed answer is never read as
 * "no groups", because that would turn a broken directory into a silent loss of access.</p>
 *
 * <p>The name sent is the caller's name on this source, which for a source with no caller authenticator of
 * its own is the caller's fallback identity. That is the same name by-name grants on the source already
 * match, so the resolver widens nothing that name could not already read on its own.</p>
 */
@Slf4j
public class HttpGroupResolver implements SourceGroupResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sourceType;
    private final String sourceId;
    private final String baseUrl;
    private final SourceRegistry.GroupDirectory directory;
    private final RestClient restClient;

    /** A resolver for one {@code rag.sources[]} entry, which must carry a validated group directory. */
    public HttpGroupResolver(SourceRegistry.Source source) {
        this.directory = source.groups();
        if (directory == null) {
            throw new IllegalArgumentException("Source " + source.type() + " has no group directory");
        }
        this.sourceType = source.type();
        this.sourceId = source.id();
        this.baseUrl = source.url().endsWith("/")
                ? source.url().substring(0, source.url().length() - 1)
                : source.url();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(directory.connectTimeout());
        factory.setReadTimeout(directory.readTimeout());
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeaders(headers -> {
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    if (source.serviceUsername() != null && !source.serviceUsername().isBlank()) {
                        headers.setBasicAuth(source.serviceUsername(),
                                source.servicePassword() == null ? "" : source.servicePassword());
                    }
                    directory.headers().forEach(headers::set);
                })
                .build();
    }

    @Override
    public String sourceType() {
        return sourceType;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public List<String> resolveGroups(String username) {
        String body;
        try {
            body = restClient.get().uri(uriFor(username)).retrieve().body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Group directory of " + describe() + " answered with an empty body");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Group directory of " + describe() + " answered with invalid JSON", e);
        }
        JsonNode array = root.at(directory.groupsPointer());
        if (!array.isArray()) {
            throw new IllegalStateException("Group directory of " + describe() + " has no array at '"
                    + directory.groupsPointer() + "'");
        }

        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (JsonNode item : array) {
            add(groups, nameOf(item));
        }
        log.debug("Resolved {} group(s) for '{}' on {}", groups.size(), username, describe());
        return List.copyOf(groups);
    }

    /** The full request URI. The name is encoded so it cannot add a path segment or a query parameter. */
    URI uriFor(String username) {
        String path = directory.path().startsWith("/") ? directory.path() : "/" + directory.path();
        String encoded = UriUtils.encode(username, StandardCharsets.UTF_8);
        return URI.create(baseUrl + path.replace(SourceRegistry.GroupDirectory.USERNAME_PLACEHOLDER, encoded));
    }

    private String nameOf(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        if (item.isObject()) {
            if (directory.groupField() == null) {
                throw new IllegalStateException("Group directory of " + describe()
                        + " returns objects; set groups.group-field to the field that names the group");
            }
            JsonNode field = item.get(directory.groupField());
            return field != null && field.isValueNode() && !field.isNull() ? field.asText() : null;
        }
        return item.isValueNode() ? item.asText() : null;
    }

    private void add(LinkedHashSet<String> groups, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        String group = candidate.trim();
        String prefix = directory.prefix();
        groups.add(prefix.isEmpty() || group.startsWith(prefix) ? group : prefix + group);
    }

    private String describe() {
        return sourceType + (sourceId == null ? "" : ":" + sourceId);
    }
}
