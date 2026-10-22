package org.hyland.alfresco.contentlake.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of a {@code contentlake.acl.changed} message from the repository module.
 *
 * <p>{@code repositoryId} names the repository that raised the change. It is {@code null} for a message
 * from a repository module that does not send it, and such a message is applied by whichever batch
 * ingester takes it. Unknown fields are ignored, so a newer repository module does not break this
 * ingester.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PermissionSyncMessage(
        String eventType,
        String sourceType,
        String repositoryId,
        String nodeId,
        boolean recursive,
        String reason,
        String occurredAt
) {
}
