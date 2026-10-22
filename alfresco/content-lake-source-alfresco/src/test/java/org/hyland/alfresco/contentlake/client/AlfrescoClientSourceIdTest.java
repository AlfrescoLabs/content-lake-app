package org.hyland.alfresco.contentlake.client;

import org.alfresco.core.handler.NodesApi;
import org.alfresco.core.model.Node;
import org.alfresco.core.model.NodeEntry;
import org.alfresco.discovery.handler.DiscoveryApi;
import org.alfresco.discovery.model.DiscoveryEntry;
import org.alfresco.discovery.model.RepositoryEntry;
import org.alfresco.discovery.model.RepositoryInfo;
import org.hyland.contentlake.spi.SourceNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which id {@link AlfrescoClient} reports as its source id.
 *
 * <p>{@code content.lake.source-id} wins when set; blank falls back to the Discovery API repository id. The
 * repository id itself never changes, because Event2 events and ACL change messages name the repository by
 * it.</p>
 */
@ExtendWith(MockitoExtension.class)
class AlfrescoClientSourceIdTest {

    private static final String REPOSITORY_ID = "repo-uuid";

    @Mock
    private NodesApi nodesApi;

    @Mock
    private DiscoveryApi discoveryApi;

    @Nested
    class WithoutOverride {

        @Test
        void sourceIdIsTheRepositoryId() {
            stubRepositoryId();

            assertThat(new AlfrescoClient(nodesApi, discoveryApi).getSourceId()).isEqualTo(REPOSITORY_ID);
        }

        @Test
        void blankOverrideMeansTheRepositoryId() {
            stubRepositoryId();

            assertThat(new AlfrescoClient(nodesApi, discoveryApi, "  ").getSourceId()).isEqualTo(REPOSITORY_ID);
        }
    }

    @Nested
    class WithOverride {

        @Test
        void sourceIdIsTheConfiguredValueWithoutAskingTheRepository() {
            AlfrescoClient client = new AlfrescoClient(nodesApi, discoveryApi, "alfresco-copy");

            assertThat(client.getSourceId()).isEqualTo("alfresco-copy");
            verifyNoInteractions(discoveryApi);
        }

        @Test
        void repositoryIdStaysTheRepositorysOwn() {
            stubRepositoryId();

            assertThat(new AlfrescoClient(nodesApi, discoveryApi, "alfresco-copy").getRepositoryId())
                    .isEqualTo(REPOSITORY_ID);
        }

        /** The node's source id is what core writes to {@code cin_sourceId} and the principal namespace. */
        @Test
        void everyNodeCarriesTheConfiguredSourceId() {
            when(nodesApi.getNode(eq("node-1"), any(), isNull(), isNull()))
                    .thenReturn(ResponseEntity.ok(new NodeEntry().entry(new Node()
                            .id("node-1").name("a.txt").isFolder(false).isFile(true))));
            AlfrescoClient client = new AlfrescoClient(nodesApi, discoveryApi, "alfresco-copy");

            SourceNode node = client.getNode("node-1");

            assertThat(node).isNotNull();
            assertThat(node.sourceId()).isEqualTo("alfresco-copy");
        }
    }

    private void stubRepositoryId() {
        lenient().when(discoveryApi.getRepositoryInformation()).thenReturn(ResponseEntity.ok(
                new DiscoveryEntry().entry(new RepositoryEntry().repository(new RepositoryInfo().id(REPOSITORY_ID)))));
    }
}
