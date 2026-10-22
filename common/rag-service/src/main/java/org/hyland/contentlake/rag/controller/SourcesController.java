package org.hyland.contentlake.rag.controller;

import lombok.RequiredArgsConstructor;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/rag/sources}: the repositories a caller can sign in to, so a UI can build one login form per
 * source (#190).
 *
 * <p>Reachable without credentials, because a login screen is drawn before anyone has signed in, and on its
 * own security chain so a stale credential the UI still attaches cannot turn it into a 401. It lists only
 * what a login form needs: no URL, no service account.</p>
 */
@RestController
@RequestMapping("/api/rag/sources")
@RequiredArgsConstructor
public class SourcesController {

    private final SourceRegistry sourceRegistry;

    /** One source as a login screen sees it. */
    public record SourceView(String sourceId, String sourceType, String displayName, List<String> loginKinds) {
    }

    @GetMapping
    public List<SourceView> sources() {
        boolean several = sourceRegistry.all().size() > 1;
        return sourceRegistry.all().stream()
                .map(source -> new SourceView(
                        // The type alone names the only source of a type whose id is discovered from the index.
                        source.id() == null ? source.type() : source.qualifiedId(),
                        source.type(),
                        several && sourceRegistry.ofType(source.type()).size() > 1 ? source.label() : typeLabel(source),
                        loginKinds(source.type())))
                .toList();
    }

    private static String typeLabel(SourceRegistry.Source source) {
        if (source.displayName() != null) {
            return source.displayName();
        }
        return Character.toUpperCase(source.type().charAt(0)) + source.type().substring(1);
    }

    private static List<String> loginKinds(String type) {
        return switch (type) {
            case SourceRegistry.ALFRESCO -> List.of("password", "ticket");
            case SourceRegistry.NUXEO -> List.of("password");
            default -> List.of();
        };
    }
}
