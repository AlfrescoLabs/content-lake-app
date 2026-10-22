package org.hyland.contentlake.rag.controller;

import org.hyland.contentlake.rag.config.SourceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourcesControllerTest {

    @Test
    void listsEachRepositoryOfASharedTypeUnderItsOwnName() {
        SourcesController controller = new SourcesController(SourceRegistry.of(List.of(
                new SourceRegistry.Source("repo-a", "alfresco", "http://a", "s", "secret", null, "Headquarters"),
                new SourceRegistry.Source("repo-b", "alfresco", "http://b", "s", "secret", null, null),
                new SourceRegistry.Source(null, "nuxeo", "http://n", "s", "secret", null, null))));

        List<SourcesController.SourceView> sources = controller.sources();

        assertThat(sources).extracting(SourcesController.SourceView::sourceId)
                .containsExactly("alfresco:repo-a", "alfresco:repo-b", "nuxeo");
        assertThat(sources).extracting(SourcesController.SourceView::displayName)
                .containsExactly("Headquarters", "Alfresco (repo-b)", "Nuxeo");
        assertThat(sources.get(0).loginKinds()).containsExactly("password", "ticket");
        assertThat(sources.get(2).loginKinds()).containsExactly("password");
    }

    @Test
    void keepsThePlainTypeNameWithOneSourcePerType() {
        SourcesController controller = new SourcesController(SourceRegistry.of(List.of(
                new SourceRegistry.Source(null, "alfresco", "http://a", "s", "secret", null, null),
                new SourceRegistry.Source(null, "nuxeo", "http://n", "s", "secret", null, null))));

        assertThat(controller.sources()).extracting(SourcesController.SourceView::displayName)
                .containsExactly("Alfresco", "Nuxeo");
    }
}
