package org.hyland.contentlake.rag.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.hyland.contentlake.security.CallerIdentities;
import org.hyland.contentlake.security.SourceIdentity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One credential per source, in {@code X-Source-Authorization} (#190).
 *
 * <pre>
 * X-Source-Authorization: &lt;sourceId&gt; Basic &lt;base64(user:password)&gt;, &lt;sourceId&gt; Ticket &lt;ticket&gt;
 * </pre>
 *
 * <p>Repeatable, and comma-joined, because browsers join repeated header values into one line before
 * sending. Each value is checked against the directory of the source it names and no other, which is what
 * lets a caller hold different passwords in two repositories of one type. {@code sourceId} is the qualified
 * {@code <type>:<id>} that {@code GET /api/rag/sources} lists, the bare id, or the type alone for the only
 * source of a type with no configured id.</p>
 *
 * <p>When the header is present it is the whole credential set: the caller gets a name in exactly the
 * sources it names, there is no fallback identity, and every other source gets no permission clause. Any
 * value that does not check out, names an unknown source, or is malformed rejects the request with 401,
 * because silently dropping it would hide a repository the caller believes they signed in to. Without the
 * header nothing here runs, so {@code Authorization} and {@code X-Nuxeo-Authorization} keep working.</p>
 */
@Slf4j
public class SourceHeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Source-Authorization";

    private final SourceRegistry sourceRegistry;
    private final SourceCredentialChecker checker;

    public SourceHeaderAuthenticationFilter(SourceRegistry sourceRegistry, SourceCredentialChecker checker) {
        this.sourceRegistry = sourceRegistry;
        this.checker = checker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> values = values(request);
        if (values.isEmpty() || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        CallerIdentities.Builder identities = CallerIdentities.builder();
        for (String value : values) {
            Optional<SourceIdentity> identity = check(value);
            if (identity.isEmpty()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid " + HEADER + " credential");
                return;
            }
            try {
                identities.add(identity.get());
            } catch (IllegalStateException e) {
                // Two names for one source: which one wins would decide what the caller reads.
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
                return;
            }
        }
        SecurityContextHolder.getContext().setAuthentication(new MultiIdentityAuthentication(identities.build()));
        chain.doFilter(new SourceHeaderStrippingRequest(request), response);
    }

    private Optional<SourceIdentity> check(String value) {
        String[] parts = value.trim().split("\\s+", 3);
        if (parts.length != 3) {
            log.debug("Malformed {} value", HEADER);
            return Optional.empty();
        }
        Optional<SourceRegistry.Source> source = resolve(parts[0]);
        if (source.isEmpty()) {
            log.debug("{} names unknown source '{}'", HEADER, parts[0]);
            return Optional.empty();
        }
        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "basic" -> basic(parts[2]).flatMap(userAndPassword ->
                    checker.passwordFor(source.get(), userAndPassword[0], userAndPassword[1]));
            case "ticket", "token" -> checker.tokenFor(source.get(), parts[2].trim());
            default -> Optional.empty();
        };
    }

    /** By qualified or bare id, or by type for the only source of a type that has no configured id. */
    private Optional<SourceRegistry.Source> resolve(String key) {
        Optional<SourceRegistry.Source> byId = sourceRegistry.byId(key);
        if (byId.isPresent()) {
            return byId;
        }
        List<SourceRegistry.Source> ofType = sourceRegistry.ofType(key);
        return ofType.size() == 1 && ofType.get(0).id() == null ? Optional.of(ofType.get(0)) : Optional.empty();
    }

    private static Optional<String[]> basic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon <= 0 ? Optional.empty()
                    : Optional.of(new String[]{decoded.substring(0, colon), decoded.substring(colon + 1)});
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Every value of the header, split on the commas browsers join repeated values with. */
    static List<String> values(HttpServletRequest request) {
        List<String> values = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders(HEADER);
        while (headers != null && headers.hasMoreElements()) {
            for (String value : headers.nextElement().split(",")) {
                if (!value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return values;
    }

    /** Keeps the credentials out of anything downstream that reads headers. */
    private static class SourceHeaderStrippingRequest extends HttpServletRequestWrapper {

        SourceHeaderStrippingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(HEADER::equalsIgnoreCase);
            return Collections.enumeration(names);
        }
    }
}
