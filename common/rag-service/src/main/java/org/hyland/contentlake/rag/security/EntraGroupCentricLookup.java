package org.hyland.contentlake.rag.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A caller's Entra groups, found from the groups' side rather than the user's.
 *
 * <p>{@code /users/{id}/transitiveMemberOf} needs a user-read permission as well as
 * {@code GroupMember.Read.All}: without one, every call under {@code /users/} answers 403, by UPN and by object
 * id alike. {@code /groups/{id}/transitiveMembers} needs only {@code GroupMember.Read.All}. So this asks, for
 * each group some indexed document is actually granted to, whether the caller is among its members.</p>
 *
 * <p>Members come back with their object id and nothing else under that permission, so only a caller known
 * by object id can be matched. That is the identity an Entra-signed-in caller has. A caller known by a UPN gets
 * {@code null}, "no such identity here", which costs them this source's group grants and nothing more.</p>
 *
 * <p>The candidate groups come from the index, not from the directory: listing every group in a tenant to
 * find the few that matter would cost far more than asking about the ones a document names. A group that no
 * document names cannot change what a caller may read, so leaving it out loses nothing.</p>
 */
@Slf4j
final class EntraGroupCentricLookup {

    /** {@code g:GROUP_<objectId>_#_<sourceId>}, as the SharePoint ACL mapper's principals are stored. */
    private static final Pattern GROUP_PRINCIPAL = Pattern.compile(
            "^g:GROUP_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})_#_.+$");

    /** Stops a runaway pager: 20 pages of 999 is a group of about 20,000 members. */
    private static final int MAX_PAGES = 20;

    private static final String CANDIDATES_KEY = "candidates";

    private final RestClient restClient;
    private final Supplier<String> accessToken;
    private final Supplier<Collection<String>> indexedPrincipals;
    private final Cache<String, Set<String>> candidates;
    private final Cache<String, Set<String>> members;

    /**
     * @param indexedPrincipals the distinct {@code sys_racl} values in the index, of which only group
     *                          principals are used
     * @param cacheTtlSeconds   how long a group's member list and the candidate list are kept; zero keeps
     *                          nothing
     */
    EntraGroupCentricLookup(RestClient restClient, Supplier<String> accessToken,
                            Supplier<Collection<String>> indexedPrincipals, long cacheTtlSeconds) {
        this.restClient = restClient;
        this.accessToken = accessToken;
        this.indexedPrincipals = indexedPrincipals;
        Duration ttl = Duration.ofSeconds(Math.max(0, cacheTtlSeconds));
        this.candidates = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(1).build();
        this.members = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    /** {@code GROUP_<objectId>} for every candidate group the caller is a transitive member of. */
    List<String> groupsOf(String objectId) {
        String caller = objectId.toLowerCase(Locale.ROOT);
        List<String> groups = new ArrayList<>();
        for (String groupId : candidateGroups()) {
            if (membersOf(groupId).contains(caller)) {
                groups.add(EntraGroupResolver.GROUP_PREFIX + groupId);
            }
        }
        return List.copyOf(groups);
    }

    private Set<String> candidateGroups() {
        Set<String> cached = candidates.getIfPresent(CANDIDATES_KEY);
        if (cached != null) {
            return cached;
        }
        Set<String> found = new LinkedHashSet<>();
        for (String principal : indexedPrincipals.get()) {
            Matcher matcher = principal == null ? null : GROUP_PRINCIPAL.matcher(principal.trim());
            if (matcher != null && matcher.matches()) {
                found.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        log.debug("Entra group-centric lookup: {} group(s) named by indexed documents", found.size());
        // An empty answer is not kept: it is the normal state of an index that is still ingesting, and keeping
        // it would hide the first group grants for a whole TTL.
        if (!found.isEmpty()) {
            candidates.put(CANDIDATES_KEY, Set.copyOf(found));
        }
        return found;
    }

    private Set<String> membersOf(String groupId) {
        Set<String> cached = members.getIfPresent(groupId);
        if (cached != null) {
            return cached;
        }
        Set<String> ids = new HashSet<>();
        String uri = "/groups/" + groupId + "/transitiveMembers?$select=id&$top=999";
        for (int page = 0; uri != null && page < MAX_PAGES; page++) {
            Map<String, Object> body;
            try {
                final String requestUri = uri;
                body = restClient.get()
                        .uri(requestUri)
                        .header("Authorization", "Bearer " + accessToken.get())
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            } catch (HttpClientErrorException.NotFound e) {
                // The group was deleted after a document was granted to it. Nobody is in it.
                log.debug("Entra has no group {}; treating it as having no members", groupId);
                break;
            }
            if (body == null) {
                break;
            }
            ids.addAll(idsFrom(body));
            uri = nextLink(body);
        }
        Set<String> result = Set.copyOf(ids);
        members.put(groupId, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> idsFrom(Map<String, Object> body) {
        Object value = body.get("value");
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> member) {
                Object id = ((Map<String, Object>) member).get("id");
                if (id != null && !id.toString().isBlank()) {
                    ids.add(id.toString().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return ids;
    }

    /** The next page as a path this client can request, keeping the query string with its skip token. */
    private static String nextLink(Map<String, Object> body) {
        Object next = body.get("@odata.nextLink");
        if (next == null) {
            return null;
        }
        String link = next.toString();
        int marker = link.indexOf("/groups/");
        return marker >= 0 ? link.substring(marker) : null;
    }
}
