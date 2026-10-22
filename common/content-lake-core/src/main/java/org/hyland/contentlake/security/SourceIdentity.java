package org.hyland.contentlake.security;

import java.util.Locale;

/**
 * One username, and the source type it is a username in.
 *
 * <p>A caller is not one name. A deployment with several content sources has several directories, and the
 * same person is a different principal in each: {@code alice} in Alfresco, {@code alice@example.com} in
 * Entra ID. An ACL stores whichever form its own source uses, so matching one against another is how a
 * caller silently reads nothing, or reads someone else's documents.</p>
 *
 * <h3>An untyped identity is not a missing one</h3>
 *
 * <p>{@code sourceType == null} means "this name is not scoped to one source type", which is exactly what
 * HTTP Basic against a repository establishes: the caller proved one credential, and the same login string
 * is all we know them by anywhere. That is the ordinary single-source case and it must keep behaving as it
 * does today, so an untyped identity answers for every source type rather than for none.</p>
 *
 * <h3>Scoped to one source</h3>
 *
 * <p>A {@code sourceId} narrows the identity to one repository of its type: the name was checked against
 * that repository and no other. A deployment with two Alfresco repositories needs this, because a login
 * proven against one says nothing about the other (#182).</p>
 *
 * @param sourceType the source type as {@code cin_sourceId} stores it ({@code "alfresco"}, {@code "nuxeo"}),
 *                   lower-cased, or {@code null} when the name is not scoped to a type
 * @param sourceId   the bare source id the name was checked against, or {@code null} when it answers for
 *                   every source of its type
 * @param username   the caller's name in that source, never null or blank
 */
public record SourceIdentity(String sourceType, String sourceId, String username) {

    public SourceIdentity {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("A source identity needs a username");
        }
        username = username.trim();
        sourceType = sourceType == null || sourceType.isBlank()
                ? null
                : sourceType.trim().toLowerCase(Locale.ROOT);
        sourceId = sourceId == null || sourceId.isBlank() ? null : sourceId.trim();
        if (sourceId != null && sourceType == null) {
            throw new IllegalArgumentException("An identity scoped to a source needs that source's type");
        }
    }

    /** An identity for every source of one type. */
    public SourceIdentity(String sourceType, String username) {
        this(sourceType, null, username);
    }

    /** A name that is not scoped to a source type, and therefore answers for all of them. */
    public static SourceIdentity untyped(String username) {
        return new SourceIdentity(null, username);
    }

    public static SourceIdentity of(String sourceType, String username) {
        return new SourceIdentity(sourceType, username);
    }

    /** A name checked against one source, which answers for that source only. */
    public static SourceIdentity scoped(String sourceType, String sourceId, String username) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("A scoped identity needs a source id");
        }
        return new SourceIdentity(sourceType, sourceId, username);
    }

    public boolean isUntyped() {
        return sourceType == null;
    }

    public boolean isScoped() {
        return sourceId != null;
    }
}
