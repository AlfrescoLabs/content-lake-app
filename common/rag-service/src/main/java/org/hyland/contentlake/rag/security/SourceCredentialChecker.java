package org.hyland.contentlake.rag.security;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.hyland.contentlake.security.CallerIdentities;
import org.hyland.contentlake.security.SourceIdentity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Checks one caller credential against every configured repository of a type (#183).
 *
 * <p>With one repository per type nothing here changes what a login means: the authenticators keep asking
 * their single directory and return the untyped identity they always have. With two or more, a login proven
 * against one repository says nothing about the others, so each is asked, in parallel, and the caller gets a
 * {@linkplain SourceIdentity#isScoped() scoped} identity for exactly the repositories that accepted it. A
 * repository that rejects the credential, or cannot be reached, gets none, so its documents get no permission
 * clause: fail closed.</p>
 */
@Slf4j
@Component
public class SourceCredentialChecker {

    private final SourceRegistry sourceRegistry;
    private final AlfrescoDirectory alfrescoDirectory;
    private final NuxeoDirectory nuxeoDirectory;

    /** One virtual thread per directory call, so N repositories cost one round trip, not N. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SourceCredentialChecker(SourceRegistry sourceRegistry, AlfrescoDirectory alfrescoDirectory,
                                   NuxeoDirectory nuxeoDirectory) {
        this.sourceRegistry = sourceRegistry;
        this.alfrescoDirectory = alfrescoDirectory;
        this.nuxeoDirectory = nuxeoDirectory;
    }

    /** Whether a type has more than one configured repository, which is when logins are checked per source. */
    public boolean checksPerSource(String sourceType) {
        return sourceRegistry != null && sourceRegistry.ofType(sourceType).size() > 1;
    }

    /**
     * A password checked against every repository of the type.
     *
     * @return the untyped fallback plus one scoped identity per accepting repository, or {@code null} when
     *         none accepted, which is the authenticator declining
     */
    public CallerIdentities password(String sourceType, String username, String password) {
        List<String> accepted = acceptingSources(sourceType, source -> switch (sourceType) {
            case SourceRegistry.ALFRESCO -> alfrescoDirectory.authenticate(source.url(), username, password);
            case SourceRegistry.NUXEO -> nuxeoDirectory.authenticate(source.url(), username, password);
            default -> false;
        });
        if (accepted.isEmpty()) {
            return null;
        }
        CallerIdentities.Builder builder = CallerIdentities.builder().fallback(SourceIdentity.untyped(username));
        accepted.forEach(sourceId -> builder.add(SourceIdentity.scoped(sourceType, sourceId, username)));
        log.debug("Authenticated '{}' via {} source(s) {}", username, sourceType, accepted);
        return builder.build();
    }

    /**
     * A ticket or token, which only the repository that issued it accepts.
     *
     * @return the untyped fallback plus a scoped identity for the issuing repository, or {@code null}
     */
    public CallerIdentities token(String sourceType, String token) {
        List<SourceRegistry.Source> sources = sourceRegistry.ofType(sourceType);
        List<CompletableFuture<Optional<String[]>>> calls = new ArrayList<>();
        for (SourceRegistry.Source source : sources) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                String username = switch (sourceType) {
                    case SourceRegistry.ALFRESCO -> alfrescoDirectory.validateTicket(source.url(), token);
                    case SourceRegistry.NUXEO -> nuxeoDirectory.validateToken(source.url(), token);
                    default -> null;
                };
                return username == null ? Optional.empty() : Optional.of(new String[]{source.id(), username});
            }, executor));
        }
        for (CompletableFuture<Optional<String[]>> call : calls) {
            Optional<String[]> hit = call.join();
            if (hit.isPresent()) {
                String[] sourceAndUser = hit.get();
                return CallerIdentities.builder()
                        .fallback(SourceIdentity.untyped(sourceAndUser[1]))
                        .add(SourceIdentity.scoped(sourceType, sourceAndUser[0], sourceAndUser[1]))
                        .build();
            }
        }
        return null;
    }

    /** One credential checked against a single, named repository, for the per-source header (#190). */
    public Optional<SourceIdentity> passwordFor(SourceRegistry.Source source, String username, String password) {
        boolean ok = switch (source.type()) {
            case SourceRegistry.ALFRESCO -> alfrescoDirectory.authenticate(source.url(), username, password);
            case SourceRegistry.NUXEO -> nuxeoDirectory.authenticate(source.url(), username, password);
            default -> false;
        };
        return ok ? Optional.of(scopedOrTyped(source, username)) : Optional.empty();
    }

    /** A ticket or token checked against a single, named repository, for the per-source header (#190). */
    public Optional<SourceIdentity> tokenFor(SourceRegistry.Source source, String token) {
        String username = switch (source.type()) {
            case SourceRegistry.ALFRESCO -> alfrescoDirectory.validateTicket(source.url(), token);
            case SourceRegistry.NUXEO -> nuxeoDirectory.validateToken(source.url(), token);
            default -> null;
        };
        return username == null ? Optional.empty() : Optional.of(scopedOrTyped(source, username));
    }

    private static SourceIdentity scopedOrTyped(SourceRegistry.Source source, String username) {
        // The only repository of its type may have no configured id; it then answers for its whole type.
        return source.id() == null
                ? SourceIdentity.of(source.type(), username)
                : SourceIdentity.scoped(source.type(), source.id(), username);
    }

    private List<String> acceptingSources(String sourceType, Function<SourceRegistry.Source, Boolean> check) {
        List<SourceRegistry.Source> sources = sourceRegistry.ofType(sourceType);
        List<CompletableFuture<Boolean>> calls = sources.stream()
                .map(source -> CompletableFuture.supplyAsync(() -> check.apply(source), executor))
                .toList();
        List<String> accepted = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (Boolean.TRUE.equals(calls.get(i).join())) {
                accepted.add(sources.get(i).id());
            }
        }
        return accepted;
    }
}
