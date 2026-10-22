package org.hyland.alfresco.contentlake.batch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncRequest;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionSyncQueueListenerTest {

    @Mock
    private PermissionSyncService permissionSyncService;

    @Mock
    private AlfrescoClient alfrescoClient;

    private PermissionSyncQueueListener listener;

    @BeforeEach
    void setUp() {
        listener = new PermissionSyncQueueListener(new ObjectMapper(), permissionSyncService, alfrescoClient);
    }

    @Test
    void onMessage_invokesPermissionReconciliation() {
        listener.onMessage("""
                {
                  "eventType":"contentlake.acl.changed",
                  "sourceType":"alfresco",
                  "nodeId":"node-1",
                  "recursive":true,
                  "reason":"inherit-permissions-disabled",
                  "occurredAt":"2026-03-30T12:00:00Z"
                }
                """);

        ArgumentCaptor<PermissionSyncRequest> request = ArgumentCaptor.forClass(PermissionSyncRequest.class);
        verify(permissionSyncService).syncPermissions(request.capture());
        assertThat(request.getValue().getNodeIds()).containsExactly("node-1");
        assertThat(request.getValue().isRecursive()).isTrue();
    }

    @Test
    void onMessage_rejectsUnsupportedEventType() {
        assertThatThrownBy(() -> listener.onMessage("""
                {
                  "eventType":"wrong.event",
                  "sourceType":"alfresco",
                  "nodeId":"node-1",
                  "recursive":false
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type");
    }

    @Test
    void onMessage_forOwnRepository_invokesPermissionReconciliation() {
        when(alfrescoClient.getRepositoryId()).thenReturn("repo-a");

        listener.onMessage(message("repo-a"));

        verify(permissionSyncService).syncPermissions(any());
    }

    /** Thrown so the transacted session rolls back and the message goes back to the broker. */
    @Test
    void onMessage_forAnotherRepository_isNotAppliedAndRollsBack() {
        when(alfrescoClient.getRepositoryId()).thenReturn("repo-a");

        assertThatThrownBy(() -> listener.onMessage(message("repo-b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repo-b");
        verify(permissionSyncService, never()).syncPermissions(any());
    }

    /** A repository module that does not send the field still works with one Alfresco. */
    @Test
    void onMessage_withoutRepositoryId_isAppliedWithoutReadingTheRepositoryId() {
        listener.onMessage(message(null));

        verify(permissionSyncService).syncPermissions(any());
        verifyNoInteractions(alfrescoClient);
    }

    @Test
    void onMessage_ignoresUnknownFields() {
        listener.onMessage("""
                {"eventType":"contentlake.acl.changed","sourceType":"alfresco","nodeId":"node-1",
                 "recursive":false,"futureField":"x"}
                """);

        verify(permissionSyncService).syncPermissions(any());
    }

    @Test
    void selectorFor_takesOwnMessagesAndThoseWithoutARepository() {
        assertThat(PermissionSyncQueueListener.selectorFor("repo-a"))
                .isEqualTo("repositoryId IS NULL OR repositoryId = 'repo-a'");
    }

    @Test
    void selectorFor_escapesQuotes() {
        assertThat(PermissionSyncQueueListener.selectorFor("o'brien"))
                .isEqualTo("repositoryId IS NULL OR repositoryId = 'o''brien'");
    }

    private static String message(String repositoryId) {
        String repository = repositoryId == null ? "" : "\"repositoryId\":\"" + repositoryId + "\",";
        return "{\"eventType\":\"contentlake.acl.changed\",\"sourceType\":\"alfresco\"," + repository
                + "\"nodeId\":\"node-1\",\"recursive\":false}";
    }
}
