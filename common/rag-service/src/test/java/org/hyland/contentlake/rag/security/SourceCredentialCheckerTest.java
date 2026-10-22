package org.hyland.contentlake.rag.security;

import com.sun.net.httpserver.HttpServer;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.hyland.contentlake.security.CallerCredentials;
import org.hyland.contentlake.security.CallerIdentities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two Alfresco repositories on two real HTTP servers, so the check reaches each directory the way it does in
 * a deployment (#183).
 */
class SourceCredentialCheckerTest {

    private HttpServer repoA;
    private HttpServer repoB;
    private SourceCredentialChecker checker;

    /** A repository that accepts exactly one password, and validates exactly one ticket, for its user. */
    private static HttpServer repository(String acceptedPassword, String ticket, String ticketUser) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alfresco/api/-default-/public/authentication/versions/1/tickets", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status;
            byte[] body = new byte[0];
            if ("POST".equals(exchange.getRequestMethod())) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                status = request.contains("\"password\":\"" + acceptedPassword + "\"") ? 201 : 403;
            } else if (path.endsWith("/-me-")) {
                String header = exchange.getRequestHeaders().getFirst("Authorization");
                String expected = "Basic " + Base64.getEncoder()
                        .encodeToString(ticket.getBytes(StandardCharsets.UTF_8));
                status = expected.equals(header) ? 200 : 401;
                body = ("{\"entry\":{\"id\":\"" + ticketUser + "\"}}").getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void setUp() throws IOException {
        repoA = repository("pw-a", "TICKET_a", "alice");
        repoB = repository("pw-b", "TICKET_b", "alice");
        SourceRegistry sources = SourceRegistry.of(List.of(
                new SourceRegistry.Source("repo-a", "alfresco", url(repoA), "svc", "svc", null, null),
                new SourceRegistry.Source("repo-b", "alfresco", url(repoB), "svc", "svc", null, null)));
        checker = new SourceCredentialChecker(sources, new AlfrescoDirectory(), new NuxeoDirectory());
    }

    @AfterEach
    void tearDown() {
        repoA.stop(0);
        repoB.stop(0);
    }

    @Test
    void checksPerSourceOnlyWhenATypeHasSeveral() {
        assertThat(checker.checksPerSource("alfresco")).isTrue();
        assertThat(checker.checksPerSource("nuxeo")).isFalse();
    }

    @Test
    void namesTheCallerOnlyInTheRepositoryThatAcceptedThePassword() {
        CallerIdentities identities = checker.password("alfresco", "alice", "pw-a");

        assertThat(identities.usernameFor("repo-a", "alfresco")).isEqualTo("alice");
        assertThat(identities.usernameFor("repo-b", "alfresco")).isNull();
        assertThat(identities.describe()).isEqualTo("alice");
    }

    @Test
    void namesTheCallerInBothWhenBothAccept() throws IOException {
        repoB.stop(0);
        repoB = repository("pw-a", "TICKET_b", "alice");
        SourceRegistry sources = SourceRegistry.of(List.of(
                new SourceRegistry.Source("repo-a", "alfresco", url(repoA), "svc", "svc", null, null),
                new SourceRegistry.Source("repo-b", "alfresco", url(repoB), "svc", "svc", null, null)));
        checker = new SourceCredentialChecker(sources, new AlfrescoDirectory(), new NuxeoDirectory());

        CallerIdentities identities = checker.password("alfresco", "alice", "pw-a");

        assertThat(identities.sourceIds()).containsExactly("repo-a", "repo-b");
    }

    @Test
    void declinesWhenNoRepositoryAccepts() {
        assertThat(checker.password("alfresco", "alice", "wrong")).isNull();
    }

    @Test
    void acceptsATicketOnlyForTheRepositoryThatIssuedIt() {
        CallerIdentities identities = checker.token("alfresco", "TICKET_b");

        assertThat(identities.usernameFor("repo-b", "alfresco")).isEqualTo("alice");
        assertThat(identities.usernameFor("repo-a", "alfresco")).isNull();
    }

    @Test
    void wiresIntoThePasswordAuthenticator() {
        var authenticator = new AlfrescoPasswordCallerAuthenticator(new AlfrescoDirectory(), checker);

        CallerIdentities identities = authenticator.authenticate(new CallerCredentials("alice", "pw-b", Map.of()));

        assertThat(identities.sourceIds()).containsExactly("repo-b");
    }

    @Test
    void rejectsAnUnknownTicketInTheTicketAuthenticator() {
        var authenticator = new AlfrescoTicketCallerAuthenticator(new AlfrescoDirectory(), checker);

        assertThatThrownBy(() -> authenticator.authenticate(new CallerCredentials("TICKET_zzz", "", Map.of())))
                .isInstanceOf(BadCredentialsException.class);
    }
}
