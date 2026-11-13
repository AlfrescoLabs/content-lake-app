package org.hyland.contentlake.rag.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.hyland.contentlake.security.AclFilterBuilder;
import org.hyland.contentlake.security.GroupResolutionFailurePolicy;
import org.hyland.contentlake.security.SourceGroupResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connector plugin's source with a configured group directory, end to end through the registry (#192):
 * the directory's answer becomes a group clause in that source's permission predicate, cached and failing
 * the way the built-in resolvers do.
 */
class HttpGroupResolverRegistryTest {

    private HttpServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private volatile int status = 200;

    @BeforeEach
    void startDirectory() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stopDirectory() {
        server.stop(0);
    }

    @Test
    void buildsAnHttpResolverOnlyForAPluginEntryThatConfiguresOne() {
        SourceRegistry sources = SourceRegistry.of(List.of(
                nextcloud("nc1", "/a/{username}"),
                new SourceRegistry.Source("fs1", "filesystem", "http://fs", null, null, null, null)));

        List<SourceGroupResolver> resolvers = SourceGroupResolverRegistry.withRepositoryResolvers(List.of(), sources);

        assertThat(resolvers).singleElement().isInstanceOf(HttpGroupResolver.class);
        assertThat(resolvers.getFirst().sourceId()).isEqualTo("nc1");
    }

    @Test
    void aMemberReadsTheGroupGrantedDocumentAndANonMemberDoesNot() {
        SourceGroupResolverRegistry registry = registry(0L, GroupResolutionFailurePolicy.FAIL_CLOSED,
                nextcloud("nc1", "/a/{username}"));

        String alice = clause(registry, "alice");
        String bob = clause(registry, "bob");

        assertThat(alice).contains("g:GROUP_finance_#_nc1");
        assertThat(bob).doesNotContain("GROUP_finance");
    }

    @Test
    void twoQueriesInsideTheTtlAskTheDirectoryOnce() {
        SourceGroupResolverRegistry registry = registry(300L, GroupResolutionFailurePolicy.FAIL_CLOSED,
                nextcloud("nc1", "/a/{username}"));

        registry.authorities("alice", "nc1", "nextcloud");
        registry.authorities("alice", "nc1", "nextcloud");

        assertThat(calls.get("/a/alice")).hasValue(1);
    }

    @Test
    void aFailureIsNotCachedAndFailClosedDropsTheSource() {
        status = 500;
        SourceGroupResolverRegistry registry = registry(300L, GroupResolutionFailurePolicy.FAIL_CLOSED,
                nextcloud("nc1", "/a/{username}"));

        assertThat(registry.authorities("alice", "nc1", "nextcloud")).isEmpty();
        assertThat(registry.authorities("alice", "nc1", "nextcloud")).isEmpty();
        assertThat(calls.get("/a/alice")).hasValue(2);
    }

    @Test
    void degradeKeepsTheCallersOwnAuthorities() {
        status = 500;
        SourceGroupResolverRegistry registry = registry(0L, GroupResolutionFailurePolicy.DEGRADE,
                nextcloud("nc1", "/a/{username}"));

        assertThat(registry.authorities("alice", "nc1", "nextcloud")).containsExactly("alice", "GROUP_EVERYONE");
    }

    @Test
    void anUnknownUserKeepsTheSourceWithDefaultAuthorities() {
        SourceGroupResolverRegistry registry = registry(0L, GroupResolutionFailurePolicy.FAIL_CLOSED,
                nextcloud("nc1", "/a/{username}"));

        assertThat(registry.authorities("nobody", "nc1", "nextcloud")).containsExactly("nobody", "GROUP_EVERYONE");
    }

    @Test
    void twoSourcesOfOneTypeEachAskTheirOwnDirectory() {
        SourceGroupResolverRegistry registry = registry(0L, GroupResolutionFailurePolicy.FAIL_CLOSED,
                nextcloud("nc1", "/a/{username}"), nextcloud("nc2", "/b/{username}"));

        assertThat(registry.authorities("alice", "nc1", "nextcloud")).contains("GROUP_finance");
        assertThat(registry.authorities("alice", "nc2", "nextcloud")).contains("GROUP_sales")
                .doesNotContain("GROUP_finance");
    }

    private String clause(SourceGroupResolverRegistry registry, String username) {
        return AclFilterBuilder.sourcePermissionClause("nc1", "nextcloud:nc1",
                registry.authorities(username, "nc1", "nextcloud"), false);
    }

    private SourceGroupResolverRegistry registry(long ttlSeconds, GroupResolutionFailurePolicy policy,
                                                 SourceRegistry.Source... sources) {
        List<SourceGroupResolver> resolvers =
                SourceGroupResolverRegistry.withRepositoryResolvers(List.of(), SourceRegistry.of(List.of(sources)));
        return new SourceGroupResolverRegistry(resolvers, policy, ttlSeconds, 100L, null);
    }

    private SourceRegistry.Source nextcloud(String id, String path) {
        return HttpGroupResolverTest.source("http://127.0.0.1:" + server.getAddress().getPort(), id,
                HttpGroupResolverTest.directory(path, "/groups", null, null));
    }

    /** /a is one directory, /b another: alice is in finance in the first and in sales in the second. */
    private void answer(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath();
            calls.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            String body;
            int code = status;
            if (code != 200) {
                body = "{}";
            } else if (path.endsWith("/nobody")) {
                code = 404;
                body = "{}";
            } else if (path.equals("/a/alice")) {
                body = "{\"groups\":[\"finance\"]}";
            } else if (path.equals("/b/alice")) {
                body = "{\"groups\":[\"sales\"]}";
            } else {
                body = "{\"groups\":[]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }
}
