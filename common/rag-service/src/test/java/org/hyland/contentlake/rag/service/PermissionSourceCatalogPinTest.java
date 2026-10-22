package org.hyland.contentlake.rag.service;

import org.hyland.contentlake.client.HxprService;
import org.hyland.contentlake.model.HxprTermsAggregationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** How configured ids of a type interact with the sources the index holds (#186). */
@ExtendWith(MockitoExtension.class)
class PermissionSourceCatalogPinTest {

    @Mock
    private HxprService hxprService;

    private PermissionSourceCatalog catalogue;

    @BeforeEach
    void setUp() {
        catalogue = new PermissionSourceCatalog(hxprService);
    }

    private void stubIndexedSources(String... qualifiedSourceIds) {
        HxprTermsAggregationResult aggregation = new HxprTermsAggregationResult();
        aggregation.setAggregationsBuckets(Arrays.stream(qualifiedSourceIds).map(key -> {
            HxprTermsAggregationResult.Bucket bucket = new HxprTermsAggregationResult.Bucket();
            bucket.setKey(key);
            bucket.setDocCount(1L);
            return bucket;
        }).toList());
        when(hxprService.termsAggregation(isNull(), eq("cin_sourceId"), isNull(), anyInt()))
                .thenReturn(aggregation);
    }

    private static PermissionSourceCatalog.Configured alfresco(String... ids) {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        byType.put("alfresco", List.of(ids));
        return new PermissionSourceCatalog.Configured(byType, null);
    }

    @Test
    void scopesATypeToEveryConfiguredIdOfIt() {
        stubIndexedSources("alfresco:repo-a", "alfresco:repo-b");

        assertThat(catalogue.resolve(alfresco("repo-a", "repo-b"), "alfresco", null))
                .containsExactly("repo-a", "repo-b");
    }

    @Test
    void keepsAPinButReportsTheIndexedSourceItLeavesOut() {
        stubIndexedSources("alfresco:repo-a", "alfresco:repo-b");

        assertThat(catalogue.resolve(alfresco("repo-a"), "alfresco", null)).containsExactly("repo-a");
        assertThat(catalogue.unlistedSources()).containsExactly("repo-b");

        // Reported once: a second request leaves the set as it was.
        catalogue.resolve(alfresco("repo-a"), "alfresco", null);
        assertThat(catalogue.unlistedSources()).containsExactly("repo-b");
    }

    @Test
    void readsTheIndexWhenTheTypeHasNoConfiguredId() {
        stubIndexedSources("alfresco:repo-a", "alfresco:repo-b", "nuxeo:nx");

        // The index yields no order, so none is asserted.
        assertThat(catalogue.resolve(new PermissionSourceCatalog.Configured(Map.of(), null), "alfresco", null))
                .containsExactlyInAnyOrder("repo-a", "repo-b");
        assertThat(catalogue.unlistedSources()).isEmpty();
    }

    @Test
    void typesASecondConfiguredSourceOfATypeBeforeItIsIndexed() {
        // Configuration answers before the index is read.
        assertThat(catalogue.sourceType(alfresco("repo-a", "repo-b"), "repo-b")).isEqualTo("alfresco");
    }
}
