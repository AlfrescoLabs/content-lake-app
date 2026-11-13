package org.hyland.contentlake.rag.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpGroupResolverTest {

    private static final String OCS_PATH = "/ocs/v1.php/cloud/users/{username}/groups?format=json";

    private HttpServer server;
    private final List<String> requestedUris = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsTheEntrysTypeAndId() {
        HttpGroupResolver resolver = new HttpGroupResolver(source("http://localhost:1", "nc1", directory(OCS_PATH,
                "/ocs/data/groups", null, null)));

        assertThat(resolver.sourceType()).isEqualTo("nextcloud");
        assertThat(resolver.sourceId()).isEqualTo("nc1");
    }

    @Test
    void readsStringGroupsAtThePointerAndPrefixesThem() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"ocs\":{\"data\":{\"groups\":[\"finance\",\"admin\"]}}}"));

        assertThat(resolver(directory(OCS_PATH, "/ocs/data/groups", null, null)).resolveGroups("alice"))
                .containsExactly("GROUP_finance", "GROUP_admin");
        assertThat(requestedUris.getFirst()).isEqualTo("/ocs/v1.php/cloud/users/alice/groups?format=json");
    }

    @Test
    void readsARootArrayWhenThePointerIsEmpty() throws Exception {
        start(exchange -> respond(exchange, 200, "[\"finance\"]"));

        assertThat(resolver(directory("/users/{username}/groups", "", null, null)).resolveGroups("alice"))
                .containsExactly("GROUP_finance");
    }

    @Test
    void readsObjectsThroughTheConfiguredField() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"groups":[{"id":"finance","name":"Finance"},{"id":"sales"},{"name":"no id"}]}"""));

        assertThat(resolver(directory("/users/{username}", "/groups", "id", null)).resolveGroups("alice"))
                .containsExactly("GROUP_finance", "GROUP_sales");
    }

    @Test
    void objectsWithoutAConfiguredFieldAreAFailureNotAnEmptyAnswer() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"groups\":[{\"id\":\"finance\"}]}"));

        assertThatThrownBy(() -> resolver(directory("/users/{username}", "/groups", null, null))
                .resolveGroups("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group-field");
    }

    @Test
    void leavesANameThatAlreadyCarriesThePrefixAlone() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"groups\":[\"GROUP_finance\",\"finance\"]}"));

        assertThat(resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .containsExactly("GROUP_finance");
    }

    @Test
    void appliesALongerConfiguredPrefix() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"groups\":[\"finance\"]}"));

        assertThat(resolver(directory("/u/{username}", "/groups", null, "GROUP_nc_")).resolveGroups("alice"))
                .containsExactly("GROUP_nc_finance");
    }

    @Test
    void everyResolvedNameIsAGroupAuthority() throws Exception {
        // AclFilterBuilder reads a name without GROUP_ as a user: a group "bob" must never become user bob.
        start(exchange -> respond(exchange, 200, "{\"groups\":[\"bob\",\"GROUP_x\"]}"));

        assertThat(resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .allMatch(name -> name.startsWith("GROUP_"));
    }

    @Test
    void ignoresBlankAndNullNames() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"groups\":[\"finance\",\"  \",null]}"));

        assertThat(resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .containsExactly("GROUP_finance");
    }

    @Test
    void anEmptyArrayIsAKnownAnswer() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"groups\":[]}"));

        assertThat(resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice")).isEmpty();
    }

    @Test
    void aNotFoundMeansTheDirectoryHoldsNoSuchIdentity() throws Exception {
        start(exchange -> respond(exchange, 404, "{}"));

        assertThat(resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice")).isNull();
    }

    @Test
    void aServerErrorPropagatesSoTheFailurePolicyApplies() throws Exception {
        start(exchange -> respond(exchange, 500, "{}"));

        assertThatThrownBy(() -> resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void anUnauthorizedServiceAccountIsAFailure() throws Exception {
        start(exchange -> respond(exchange, 401, "{}"));

        assertThatThrownBy(() -> resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void invalidJsonIsAFailure() throws Exception {
        start(exchange -> respond(exchange, 200, "<html>login</html>"));

        assertThatThrownBy(() -> resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void anEmptyBodyIsAFailure() throws Exception {
        start(exchange -> respond(exchange, 200, ""));

        assertThatThrownBy(() -> resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPointerThatMissesIsAFailure() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"other\":[\"finance\"]}"));

        assertThatThrownBy(() -> resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/groups");
    }

    @Test
    void aPointerThatReachesANonArrayIsAFailure() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"groups\":\"finance\"}"));

        assertThatThrownBy(() -> resolver(directory("/u/{username}", "/groups", null, null)).resolveGroups("alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSlowDirectoryTimesOutAsAFailure() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"groups\":[]}");
        });
        SourceRegistry.GroupDirectory slow = new SourceRegistry.GroupDirectory("http", "/u/{username}", Map.of(),
                "/groups", null, null, Duration.ofSeconds(1), Duration.ofMillis(200));

        assertThatThrownBy(() -> resolver(slow).resolveGroups("alice")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void encodesTheNameSoItCannotAddAPathSegmentOrAQueryParameter() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"groups\":[]}"));

        resolver(directory("/u/{username}/groups?format=json", "/groups", null, null)).resolveGroups("a b&x=1/y");

        assertThat(requestedUris.getFirst()).isEqualTo("/u/a%20b%26x%3D1%2Fy/groups?format=json");
    }

    @Test
    void authenticatesWithTheServiceAccountAndSendsTheConfiguredHeaders() throws Exception {
        List<String> authorizations = new ArrayList<>();
        List<String> ocsHeaders = new ArrayList<>();
        start(exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            ocsHeaders.add(exchange.getRequestHeaders().getFirst("OCS-APIRequest"));
            respond(exchange, 200, "{\"groups\":[]}");
        });
        SourceRegistry.GroupDirectory withHeader = new SourceRegistry.GroupDirectory("http", "/u/{username}",
                Map.of("OCS-APIRequest", "true"), "/groups", null, null, Duration.ofSeconds(3), Duration.ofSeconds(5));

        resolver(withHeader).resolveGroups("alice");

        assertThat(authorizations).containsExactly("Basic " + Base64.getEncoder()
                .encodeToString("svc:secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(ocsHeaders).containsExactly("true");
    }

    @Test
    void sendsNoAuthorizationWhenTheEntryHasNoServiceAccount() throws Exception {
        List<String> authorizations = new ArrayList<>();
        start(exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"groups\":[]}");
        });
        SourceRegistry.Source anonymous = new SourceRegistry.Source("nc1", "nextcloud", baseUrl(), null, null, null,
                null, directory("/u/{username}", "/groups", null, null));

        new HttpGroupResolver(anonymous).resolveGroups("alice");

        assertThat(authorizations).containsOnlyNulls();
    }

    @Test
    void toleratesABaseUrlWithATrailingSlashAndAPathWithoutALeadingOne() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"groups\":[]}"));

        new HttpGroupResolver(source(baseUrl() + "/", "nc1", directory("u/{username}", "/groups", null, null)))
                .resolveGroups("alice");

        assertThat(requestedUris.getFirst()).isEqualTo("/u/alice");
    }

    static SourceRegistry.GroupDirectory directory(String path, String pointer, String groupField, String prefix) {
        return new SourceRegistry.GroupDirectory("http", path, Map.of(), pointer, groupField, prefix,
                Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    static SourceRegistry.Source source(String url, String id, SourceRegistry.GroupDirectory directory) {
        return new SourceRegistry.Source(id, "nextcloud", url, "svc", "secret", null, null, directory);
    }

    private HttpGroupResolver resolver(SourceRegistry.GroupDirectory directory) {
        return new HttpGroupResolver(source(baseUrl(), "nc1", directory));
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try {
                requestedUris.add(exchange.getRequestURI().getRawPath()
                        + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    @FunctionalInterface
    interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
