package org.hyland.alfresco.contentlake.repo.acl;

import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.service.descriptor.Descriptor;
import org.alfresco.service.descriptor.DescriptorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Sends one {@code contentlake.acl.changed} message per ACL change to the batch ingester's queue.
 *
 * <p>Each message names the repository it came from in {@code repositoryId}, both as a JMS string property
 * and in the JSON body, so two batch ingesters on one broker each take only their own repository's
 * messages. The id is the repository descriptor id, the same value the Discovery API and Event2
 * {@code source} report.</p>
 */
@Slf4j
@Component
class AclChangeJmsPublisher implements AclChangePublisher {

    private final boolean enabled;
    private final String queueName;
    private final JmsTemplate jmsTemplate;
    private final DescriptorService descriptorService;

    AclChangeJmsPublisher(
            @Value("${content.lake.permission.sync.enabled:false}") boolean enabled,
            @Value("${content.lake.permission.sync.queue-name:contentlake.acl.changed}") String queueName,
            JmsTemplate contentLakePermissionSyncJmsTemplate,
            @Qualifier("DescriptorService") DescriptorService descriptorService
    ) {
        this.enabled = enabled;
        this.queueName = queueName;
        this.jmsTemplate = contentLakePermissionSyncJmsTemplate;
        this.descriptorService = descriptorService;
    }

    @Override
    public void publish(AclChangeRequest request) {
        if (!enabled) {
            return;
        }
        String repositoryId = repositoryId();
        String payload = payloadFor(request, repositoryId, Instant.now());
        jmsTemplate.send(queueName, session -> {
            TextMessage message = session.createTextMessage(payload);
            setStringProperty(message, "eventType", "contentlake.acl.changed");
            setStringProperty(message, "sourceType", "alfresco");
            if (repositoryId != null) {
                setStringProperty(message, "repositoryId", repositoryId);
            }
            setStringProperty(message, "nodeId", request.nodeId());
            message.setBooleanProperty("recursive", request.recursive());
            return message;
        });
        log.debug("Published ACL reconciliation message for node {} recursive={}", request.nodeId(), request.recursive());
    }

    /**
     * The JSON body. {@code repositoryId} is left out when it is not known, which the batch ingester reads
     * as a message for whichever repository it serves.
     */
    static String payloadFor(AclChangeRequest request, String repositoryId, Instant occurredAt) {
        return "{"
                + "\"eventType\":\"contentlake.acl.changed\","
                + "\"sourceType\":\"alfresco\","
                + (repositoryId != null ? "\"repositoryId\":\"" + escapeJson(repositoryId) + "\"," : "")
                + "\"nodeId\":\"" + escapeJson(request.nodeId()) + "\","
                + "\"recursive\":" + request.recursive() + ","
                + "\"reason\":\"" + escapeJson(request.reason()) + "\","
                + "\"occurredAt\":\"" + occurredAt + "\""
                + "}";
    }

    /** Read per message: the descriptor is not available until the repository has bootstrapped. */
    private String repositoryId() {
        Descriptor descriptor = descriptorService.getCurrentRepositoryDescriptor();
        String id = descriptor != null ? descriptor.getId() : null;
        return id == null || id.isBlank() ? null : id;
    }

    private static void setStringProperty(TextMessage message, String key, String value) throws JMSException {
        message.setStringProperty(key, value);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
