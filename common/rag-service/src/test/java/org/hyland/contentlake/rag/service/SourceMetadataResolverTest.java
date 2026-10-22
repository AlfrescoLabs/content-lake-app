package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.model.ContentLakeIngestProperties;
import org.hyland.contentlake.model.HxprDocument;
import org.hyland.contentlake.rag.config.RagProperties;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.hyland.contentlake.rag.model.SemanticSearchResponse.SourceDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SourceMetadataResolverTest {

    @Mock
    Environment environment;

    private SourceMetadataResolver resolver;

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getSourceLinks().setAlfrescoTemplate(
                "http://localhost:80/share/page/document-details?nodeRef=workspace://SpacesStore/{nodeId}"
        );
        ragProperties.getSourceLinks().setNuxeoTemplate(
                "http://localhost:8081/nuxeo/ui/#!/browse{nuxeoPath}"
        );

        lenient().when(environment.resolvePlaceholders(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        resolver = new SourceMetadataResolver(ragProperties, environment);
    }

    @Test
    void resolveSourceDocument_alfrescoDocument_buildsShareLink() {
        HxprDocument doc = new HxprDocument();
        doc.setCinId("550e8400-e29b-41d4-a716-446655440000");
        doc.setCinSourceId("alfresco:repo-main");
        doc.setCinIngestProperties(Map.of(
                ContentLakeIngestProperties.SOURCE_TYPE, "alfresco",
                ContentLakeIngestProperties.SOURCE_NAME, "Budget 2026.pdf",
                ContentLakeIngestProperties.SOURCE_PATH, "/Company Home/Sites/finance/documentLibrary",
                ContentLakeIngestProperties.SOURCE_MIME_TYPE, "application/pdf"
        ));

        SourceDocument sourceDocument = resolver.resolveSourceDocument("doc-1", doc);

        assertThat(sourceDocument.getSourceType()).isEqualTo("alfresco");
        assertThat(sourceDocument.getSourceId()).isEqualTo("alfresco:repo-main");
        assertThat(sourceDocument.getName()).isEqualTo("Budget 2026.pdf");
        assertThat(sourceDocument.getPath()).isEqualTo("/Company Home/Sites/finance/documentLibrary");
        assertThat(sourceDocument.getMimeType()).isEqualTo("application/pdf");
        assertThat(sourceDocument.getOpenInSourceUrl())
                .isEqualTo("http://localhost:80/share/page/document-details?nodeRef=workspace://SpacesStore/550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void resolveSourceDocument_nuxeoDocument_prefersNuxeoPathAndEncodesSpaces() {
        HxprDocument doc = new HxprDocument();
        doc.setCinId("660e8400-e29b-41d4-a716-446655440000");
        doc.setCinSourceId("nuxeo:nuxeo-demo");
        doc.setCinIngestProperties(Map.of(
                ContentLakeIngestProperties.SOURCE_TYPE, "nuxeo",
                ContentLakeIngestProperties.SOURCE_NAME, "Meeting Notes 2026.docx",
                ContentLakeIngestProperties.SOURCE_PATH, "/default-domain/workspaces/finance",
                ContentLakeIngestProperties.NUXEO_PATH, "/default-domain/workspaces/finance/Meeting Notes 2026.docx"
        ));

        SourceDocument sourceDocument = resolver.resolveSourceDocument("doc-2", doc);

        assertThat(sourceDocument.getSourceType()).isEqualTo("nuxeo");
        assertThat(sourceDocument.getOpenInSourceUrl())
                .isEqualTo("http://localhost:8081/nuxeo/ui/#!/browse/default-domain/workspaces/finance/Meeting%20Notes%202026.docx");
    }

    private static HxprDocument alfrescoDoc(String cinSourceId) {
        HxprDocument doc = new HxprDocument();
        doc.setCinId("node-1");
        doc.setCinSourceId(cinSourceId);
        doc.setCinIngestProperties(Map.of(ContentLakeIngestProperties.SOURCE_TYPE, "alfresco",
                ContentLakeIngestProperties.SOURCE_NAME, "a.pdf"));
        return doc;
    }

    @Test
    void linksEachRepositoryOfOneTypeToItself() {
        resolver.setSourceRegistry(SourceRegistry.of(List.of(
                new SourceRegistry.Source("repo-a", "alfresco", "http://a", "s", "s", "http://a/aca/{nodeId}", null),
                new SourceRegistry.Source("repo-b", "alfresco", "http://b", "s", "s", "http://b/aca/{nodeId}", null))));

        assertThat(resolver.resolveSourceDocument("d", alfrescoDoc("alfresco:repo-a")).getOpenInSourceUrl())
                .isEqualTo("http://a/aca/node-1");
        assertThat(resolver.resolveSourceDocument("d", alfrescoDoc("alfresco:repo-b")).getOpenInSourceUrl())
                .isEqualTo("http://b/aca/node-1");
    }

    @Test
    void fallsBackToTheTypeTemplateForASourceWithoutOne() {
        resolver.setSourceRegistry(SourceRegistry.of(List.of(
                new SourceRegistry.Source("repo-a", "alfresco", "http://a", "s", "s", "http://a/aca/{nodeId}", null),
                new SourceRegistry.Source("repo-b", "alfresco", "http://b", "s", "s", null, null))));

        assertThat(resolver.resolveSourceDocument("d", alfrescoDoc("alfresco:repo-b")).getOpenInSourceUrl())
                .isEqualTo("http://localhost:80/share/page/document-details?nodeRef=workspace://SpacesStore/node-1");
    }

    @Test
    void buildsTheTypeDefaultOnTheSourcesOwnUrl() {
        RagProperties properties = new RagProperties();
        properties.getSourceLinks().setAlfrescoTemplate("${content.service.url}/share/{nodeId}");
        SourceMetadataResolver withDefault = new SourceMetadataResolver(properties, environment);
        withDefault.setSourceRegistry(SourceRegistry.of(List.of(
                new SourceRegistry.Source("repo-a", "alfresco", "http://a", "s", "s", null, null),
                new SourceRegistry.Source("repo-b", "alfresco", "http://b", "s", "s", null, null))));

        assertThat(withDefault.resolveSourceDocument("d", alfrescoDoc("alfresco:repo-b")).getOpenInSourceUrl())
                .isEqualTo("http://b/share/node-1");
    }
}
