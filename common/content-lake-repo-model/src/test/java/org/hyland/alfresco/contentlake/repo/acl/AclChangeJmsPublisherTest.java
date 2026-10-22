package org.hyland.alfresco.contentlake.repo.acl;

import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.alfresco.service.descriptor.Descriptor;
import org.alfresco.service.descriptor.DescriptorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every ACL change message names its repository, so two batch ingesters on one broker can each select
 * their own repository's messages.
 */
@ExtendWith(MockitoExtension.class)
class AclChangeJmsPublisherTest {

    private static final AclChangeRequest REQUEST = new AclChangeRequest("node-1", true, "inherit-permissions-disabled");

    @Mock
    private JmsTemplate jmsTemplate;

    @Mock
    private DescriptorService descriptorService;

    @Mock
    private Descriptor descriptor;

    @Mock
    private Session session;

    @Mock
    private TextMessage textMessage;

    private AclChangeJmsPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AclChangeJmsPublisher(true, "contentlake.acl.changed", jmsTemplate, descriptorService);
    }

    @Test
    void publish_setsTheRepositoryIdPropertyAndJsonField() throws Exception {
        when(descriptorService.getCurrentRepositoryDescriptor()).thenReturn(descriptor);
        when(descriptor.getId()).thenReturn("repo-a");
        when(session.createTextMessage(anyString())).thenReturn(textMessage);

        publisher.publish(REQUEST);

        createdMessage();
        verify(textMessage).setStringProperty("repositoryId", "repo-a");
        verify(textMessage).setStringProperty("nodeId", "node-1");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(session).createTextMessage(payload.capture());
        assertThat(payload.getValue()).contains("\"repositoryId\":\"repo-a\"");
    }

    /** Before the repository has bootstrapped there is no descriptor; the message still goes out. */
    @Test
    void publish_withoutADescriptor_omitsTheRepositoryId() throws Exception {
        when(session.createTextMessage(anyString())).thenReturn(textMessage);

        publisher.publish(REQUEST);

        createdMessage();
        verify(textMessage, never()).setStringProperty(eq("repositoryId"), anyString());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(session).createTextMessage(payload.capture());
        assertThat(payload.getValue()).doesNotContain("repositoryId");
    }

    @Test
    void payloadFor_placesTheRepositoryIdBeforeTheNodeId() {
        String payload = AclChangeJmsPublisher.payloadFor(REQUEST, "repo-\"a\"", Instant.parse("2026-10-22T10:00:00Z"));

        assertThat(payload).isEqualTo("{"
                + "\"eventType\":\"contentlake.acl.changed\","
                + "\"sourceType\":\"alfresco\","
                + "\"repositoryId\":\"repo-\\\"a\\\"\","
                + "\"nodeId\":\"node-1\","
                + "\"recursive\":true,"
                + "\"reason\":\"inherit-permissions-disabled\","
                + "\"occurredAt\":\"2026-10-22T10:00:00Z\""
                + "}");
    }

    @Test
    void publish_whenDisabled_sendsNothing() {
        new AclChangeJmsPublisher(false, "contentlake.acl.changed", jmsTemplate, descriptorService).publish(REQUEST);

        verify(jmsTemplate, never()).send(anyString(), any(MessageCreator.class));
    }

    private void createdMessage() throws Exception {
        ArgumentCaptor<MessageCreator> creator = ArgumentCaptor.forClass(MessageCreator.class);
        verify(jmsTemplate).send(eq("contentlake.acl.changed"), creator.capture());
        creator.getValue().createMessage(session);
    }
}
