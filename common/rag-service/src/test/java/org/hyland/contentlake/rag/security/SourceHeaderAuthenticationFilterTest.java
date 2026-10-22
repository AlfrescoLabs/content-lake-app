package org.hyland.contentlake.rag.security;

import jakarta.servlet.http.HttpServletRequest;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.hyland.contentlake.security.CallerIdentities;
import org.hyland.contentlake.security.SourceIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SourceHeaderAuthenticationFilterTest {

    private static final SourceRegistry.Source REPO_A =
            new SourceRegistry.Source("repo-a", "alfresco", "http://a", "s", "s", null, null);
    private static final SourceRegistry.Source REPO_B =
            new SourceRegistry.Source("repo-b", "alfresco", "http://b", "s", "s", null, null);

    @Mock
    private SourceCredentialChecker checker;

    private SourceHeaderAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SourceHeaderAuthenticationFilter(SourceRegistry.of(List.of(REPO_A, REPO_B)), checker);
        lenient().when(checker.passwordFor(eq(REPO_A), eq("alice"), eq("pw-a")))
                .thenReturn(Optional.of(SourceIdentity.scoped("alfresco", "repo-a", "alice")));
        lenient().when(checker.passwordFor(eq(REPO_B), eq("a.smith"), eq("pw-b")))
                .thenReturn(Optional.of(SourceIdentity.scoped("alfresco", "repo-b", "a.smith")));
        lenient().when(checker.passwordFor(any(), any(), eq("wrong"))).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void givesOneIdentityPerSourceFromACommaJoinedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SourceHeaderAuthenticationFilter.HEADER,
                "alfresco:repo-a " + basic("alice", "pw-a") + ", alfresco:repo-b " + basic("a.smith", "pw-b"));
        MockFilterChain chain = new MockFilterChain();

        run(request, chain);

        CallerIdentities identities = ((MultiIdentityAuthentication) SecurityContextHolder.getContext()
                .getAuthentication()).identities();
        assertThat(identities.usernameFor("repo-a", "alfresco")).isEqualTo("alice");
        assertThat(identities.usernameFor("repo-b", "alfresco")).isEqualTo("a.smith");
        // No fallback: a source the header does not name gets no clause.
        assertThat(identities.usernameFor("nuxeo-demo", "nuxeo")).isNull();
        // The credentials do not travel further down the chain.
        assertThat(chain.getRequest()).isNotNull();
        assertThat(((HttpServletRequest) chain.getRequest())
                .getHeader(SourceHeaderAuthenticationFilter.HEADER)).isNull();
    }

    @Test
    void acceptsRepeatedHeaderLines() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SourceHeaderAuthenticationFilter.HEADER, "repo-a " + basic("alice", "pw-a"));
        request.addHeader(SourceHeaderAuthenticationFilter.HEADER, "repo-b " + basic("a.smith", "pw-b"));

        run(request, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void rejectsAWrongCredential() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SourceHeaderAuthenticationFilter.HEADER, "repo-a " + basic("alice", "wrong"));
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(request, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsAnUnknownSourceInsteadOfIgnoringIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SourceHeaderAuthenticationFilter.HEADER, "alfresco:repo-x " + basic("alice", "pw-a"));

        assertThat(run(request, new MockFilterChain()).getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsAMalformedValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SourceHeaderAuthenticationFilter.HEADER, "repo-a");

        assertThat(run(request, new MockFilterChain()).getStatus()).isEqualTo(401);
    }

    @Test
    void leavesARequestWithoutTheHeaderToTheOtherFilters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", basic("alice", "pw-a"));
        MockFilterChain chain = new MockFilterChain();

        run(request, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(checker);
    }
}
