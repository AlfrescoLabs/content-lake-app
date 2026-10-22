package org.hyland.contentlake.rag.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured source ids, now keyed by type rather than one field per known source.
 *
 * <p>There was no test for this record. It decides which sources a permission filter covers and how each is
 * typed, so the reshape needed one.</p>
 */
class PermissionSourceCatalogConfiguredTest {

    @Test
    void keepsTheOrderTheTwoInTreeSourcesHaveAlwaysBeenCheckedIn() {
        PermissionSourceCatalog.Configured configured =
                PermissionSourceCatalog.Configured.of("test-repo", "nuxeo-demo", null);

        // Alfresco before Nuxeo. A source id configured for two types would otherwise be typed by whichever
        // the map happened to yield first.
        assertThat(configured.configuredIds()).containsExactly("test-repo", "nuxeo-demo");
    }

    @Test
    void omitsATypeThatIsNotConfigured() {
        PermissionSourceCatalog.Configured configured =
                PermissionSourceCatalog.Configured.of("test-repo", "  ", null);

        assertThat(configured.configuredIds()).containsExactly("test-repo");
        assertThat(configured.sourceIdsOf("nuxeo")).isEmpty();
    }

    @Test
    void resolvesASourceIdByType() {
        PermissionSourceCatalog.Configured configured =
                PermissionSourceCatalog.Configured.of("test-repo", "nuxeo-demo", null);

        assertThat(configured.sourceIdsOf("alfresco")).containsExactly("test-repo");
        assertThat(configured.sourceIdsOf("nuxeo")).containsExactly("nuxeo-demo");
        assertThat(configured.sourceIdsOf("NUXEO")).containsExactly("nuxeo-demo");
        assertThat(configured.sourceIdsOf("sharepoint")).isEmpty();
        assertThat(configured.sourceIdsOf(null)).isEmpty();
    }

    @Test
    void carriesATypeTheServicesWereNotCompiledAgainst() {
        // The point of the reshape: a fourth source type needs no change to this record.
        Map<String, List<String>> byType = new LinkedHashMap<>();
        byType.put("sharepoint", List.of("site-1"));
        byType.put("cmis", List.of("docbase-1"));

        PermissionSourceCatalog.Configured configured = new PermissionSourceCatalog.Configured(byType, null);

        assertThat(configured.sourceIdsOf("sharepoint")).containsExactly("site-1");
        assertThat(configured.sourceIdsOf("cmis")).containsExactly("docbase-1");
        assertThat(configured.configuredIds()).containsExactly("site-1", "docbase-1");
    }

    @Test
    void holdsSeveralSourcesOfOneType() {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        byType.put("alfresco", List.of("repo-a", "repo-b"));
        byType.put("nuxeo", List.of("nuxeo-demo"));

        PermissionSourceCatalog.Configured configured = new PermissionSourceCatalog.Configured(byType, null);

        assertThat(configured.sourceIdsOf("alfresco")).containsExactly("repo-a", "repo-b");
        assertThat(configured.configuredIds()).containsExactly("repo-a", "repo-b", "nuxeo-demo");
    }

    @Test
    void reportsWhetherAnOperatorPinnedTheSet() {
        assertThat(PermissionSourceCatalog.Configured.of("a", "b", null).hasPin()).isFalse();
        assertThat(PermissionSourceCatalog.Configured.of("a", "b", "  ").hasPin()).isFalse();
        assertThat(PermissionSourceCatalog.Configured.of("a", "b", "x,y").hasPin()).isTrue();
    }

    @Test
    void isDefensiveAboutTheMapItWasGiven() {
        Map<String, List<String>> mutable = new LinkedHashMap<>();
        mutable.put("alfresco", new ArrayList<>(List.of("test-repo")));
        PermissionSourceCatalog.Configured configured =
                new PermissionSourceCatalog.Configured(mutable, null);

        mutable.put("nuxeo", List.of("added-later"));
        mutable.get("alfresco").add("added-later-too");

        // A caller mutating its own map must not change what sources a permission filter covers.
        assertThat(configured.sourceIdsOf("nuxeo")).isEmpty();
        assertThat(configured.configuredIds()).containsExactly("test-repo");
    }

    @Test
    void toleratesNoConfiguredSourceAtAll() {
        PermissionSourceCatalog.Configured configured =
                new PermissionSourceCatalog.Configured(null, null);

        assertThat(configured.configuredIds()).isEmpty();
        assertThat(configured.sourceIdsOf("alfresco")).isEmpty();
        assertThat(configured.hasPin()).isFalse();
    }
}
