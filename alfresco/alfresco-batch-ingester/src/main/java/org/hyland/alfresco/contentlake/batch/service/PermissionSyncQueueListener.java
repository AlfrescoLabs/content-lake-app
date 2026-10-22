package org.hyland.alfresco.contentlake.batch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyland.alfresco.contentlake.batch.config.JmsConfig;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncMessage;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncRequest;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies ACL change messages from the repository module.
 *
 * <p>The queue is point-to-point, so on a broker shared by two repositories each message must reach the
 * batch ingester of the repository that raised it. The listener container therefore does not start with
 * the context: {@link PermissionSyncListenerStarter} starts it once the repository id is known, with the
 * selector from {@link #selectorFor(String)}. A message for another repository then stays on the queue for
 * its own consumer, and a message without a {@code repositoryId} is taken by any consumer.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionSyncQueueListener {

    /** Id of the listener container in the {@code JmsListenerEndpointRegistry}. */
    public static final String LISTENER_ID = "permissionSyncQueueListener";

    /** JMS string property, and JSON field, naming the repository that raised the change. */
    public static final String REPOSITORY_ID_PROPERTY = "repositoryId";

    private static final String EVENT_TYPE = "contentlake.acl.changed";
    private static final String SOURCE_TYPE = "alfresco";

    private final ObjectMapper objectMapper;
    private final PermissionSyncService permissionSyncService;
    private final AlfrescoClient alfrescoClient;

    @Value("${content.lake.permission.sync.queue-name:contentlake.acl.changed}")
    private String queueName;

    @JmsListener(id = LISTENER_ID,
            destination = "${content.lake.permission.sync.queue-name:contentlake.acl.changed}",
            containerFactory = JmsConfig.PERMISSION_SYNC_CONTAINER_FACTORY)
    public void onMessage(String payload) {
        PermissionSyncMessage message = parse(payload);
        validate(message);
        rejectOtherRepository(message);

        PermissionSyncRequest request = new PermissionSyncRequest();
        request.setNodeIds(List.of(message.nodeId()));
        request.setRecursive(message.recursive());

        permissionSyncService.syncPermissions(request);
        log.debug("Processed ACL reconciliation message from queue {} for node {} recursive={}",
                queueName, message.nodeId(), message.recursive());
    }

    /**
     * The message selector for the batch ingester of {@code repositoryId}: its own messages, and those that
     * name no repository.
     */
    public static String selectorFor(String repositoryId) {
        return REPOSITORY_ID_PROPERTY + " IS NULL OR " + REPOSITORY_ID_PROPERTY + " = '"
                + repositoryId.replace("'", "''") + "'";
    }

    /**
     * The selector keeps another repository's messages away from this listener. Should one arrive anyway,
     * throwing rolls the session back so the message returns to the broker instead of being acknowledged
     * and lost: applying it here would find no node and drop the permission change.
     */
    private void rejectOtherRepository(PermissionSyncMessage message) {
        String messageRepositoryId = message.repositoryId();
        if (messageRepositoryId == null || messageRepositoryId.isBlank()) {
            return;
        }
        String ownRepositoryId = alfrescoClient.getRepositoryId();
        if (!messageRepositoryId.equals(ownRepositoryId)) {
            throw new IllegalStateException("ACL reconciliation message for repository " + messageRepositoryId
                    + " reached the ingester of repository " + ownRepositoryId);
        }
    }

    private PermissionSyncMessage parse(String payload) {
        try {
            return objectMapper.readValue(payload, PermissionSyncMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid ACL reconciliation message payload", e);
        }
    }

    private static void validate(PermissionSyncMessage message) {
        if (message == null || !EVENT_TYPE.equals(message.eventType())) {
            throw new IllegalArgumentException("Unsupported ACL reconciliation event type");
        }
        if (!SOURCE_TYPE.equals(message.sourceType())) {
            throw new IllegalArgumentException("Unsupported ACL reconciliation source type");
        }
        if (message.nodeId() == null || message.nodeId().isBlank()) {
            throw new IllegalArgumentException("ACL reconciliation message nodeId must not be blank");
        }
    }
}
