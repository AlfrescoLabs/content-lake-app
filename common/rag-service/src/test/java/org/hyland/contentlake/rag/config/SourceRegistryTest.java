package org.hyland.contentlake.rag.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceRegistryTest {

    private static RagProperties.SourceProperties entry(String id, String type, String url) {
        RagProperties.SourceProperties p = new RagProperties.SourceProperties();
        p.setId(id);
        p.setType(type);
        p.setUrl(url);
        p.setServiceUsername("svc");
        p.setServicePassword("secret");
        return p;
    }

    private static SourceRegistry registry(List<RagProperties.SourceProperties> sources,
                                           String alfrescoSourceId, String nuxeoUrl) {
        RagProperties properties = new RagProperties();
        properties.setSources(sources);
        return new SourceRegistry(properties, "http://alfresco:8080", "admin", "admin", alfrescoSourceId,
                nuxeoUrl, "Administrator", "Administrator", "");
    }

    @Nested
    class WithoutTheList {

        @Test
        void buildsOneEntryPerTypeFromTheLegacyKeys() {
            SourceRegistry registry = registry(List.of(), "", "http://nuxeo:8080/nuxeo");

            assertThat(registry.all()).extracting(SourceRegistry.Source::type).containsExactly("alfresco", "nuxeo");
            assertThat(registry.ofType("alfresco").get(0).url()).isEqualTo("http://alfresco:8080");
            assertThat(registry.ofType("alfresco").get(0).serviceUsername()).isEqualTo("admin");
            // No id configured: the id stays discovered from the index, as before.
            assertThat(registry.ofType("alfresco").get(0).id()).isNull();
            assertThat(registry.configuredIdsByType()).isEmpty();
        }

        @Test
        void keepsAConfiguredLegacySourceId() {
            SourceRegistry registry = registry(List.of(), "alfresco:repo-a", "");

            assertThat(registry.all()).hasSize(1);
            assertThat(registry.configuredIdsByType()).isEqualTo(Map.of("alfresco", List.of("repo-a")));
        }

        @Test
        void answersForAnySourceOfTheTypeWhenTheOnlyEntryHasNoId() {
            SourceRegistry registry = registry(List.of(), "", "");

            assertThat(registry.forSource("whatever-id", "alfresco")).isPresent();
        }
    }

    @Nested
    class WithTheList {

        @Test
        void holdsTwoSourcesOfOneType() {
            SourceRegistry registry = registry(List.of(
                    entry("repo-a", "alfresco", "http://alfresco:8080"),
                    entry("repo-b", "Alfresco", "http://alfresco-2:8080")), "", "http://ignored");

            assertThat(registry.ofType("alfresco")).extracting(SourceRegistry.Source::url)
                    .containsExactly("http://alfresco:8080", "http://alfresco-2:8080");
            assertThat(registry.configuredIdsByType()).isEqualTo(Map.of("alfresco", List.of("repo-a", "repo-b")));
            // The list replaces the legacy keys entirely.
            assertThat(registry.ofType("nuxeo")).isEmpty();
        }

        @Test
        void findsAnEntryByBareOrQualifiedId() {
            SourceRegistry registry = registry(List.of(
                    entry("repo-a", "alfresco", "http://a"), entry("repo-b", "alfresco", "http://b")), "", "");

            assertThat(registry.byId("repo-b").orElseThrow().url()).isEqualTo("http://b");
            assertThat(registry.byId("alfresco:repo-a").orElseThrow().url()).isEqualTo("http://a");
        }

        @Test
        void doesNotGuessADirectoryForAnUnconfiguredSourceOfASharedType() {
            SourceRegistry registry = registry(List.of(
                    entry("repo-a", "alfresco", "http://a"), entry("repo-b", "alfresco", "http://b")), "", "");

            assertThat(registry.forSource("repo-c", "alfresco")).isEmpty();
        }

        @Test
        void refusesTwoEntriesOfOneTypeWithoutIds() {
            assertThatThrownBy(() -> registry(List.of(
                    entry("repo-a", "alfresco", "http://a"), entry(null, "alfresco", "http://b")), "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("http://b");
        }

        @Test
        void refusesADuplicateIdEvenAcrossTypes() {
            assertThatThrownBy(() -> registry(List.of(
                    entry("default", "alfresco", "http://a"), entry("default", "nuxeo", "http://b")), "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("default");
        }

        @Test
        void refusesAnEntryWithoutAUrl() {
            assertThatThrownBy(() -> registry(List.of(entry("repo-a", "alfresco", " ")), "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no url");
        }
    }

    @Nested
    class WithAGroupDirectory {

        private RagProperties.SourceProperties plugin(String kind, String path) {
            RagProperties.SourceProperties p = entry("nc1", "nextcloud", "https://cloud.example.org");
            RagProperties.GroupDirectoryProperties groups = new RagProperties.GroupDirectoryProperties();
            groups.setKind(kind);
            groups.setPath(path);
            groups.setGroupsPointer("/ocs/data/groups");
            groups.getHeaders().put("OCS-APIRequest", "true");
            p.setGroups(groups);
            return p;
        }

        @Test
        void bindsTheDirectoryOntoTheEntry() {
            SourceRegistry registry = registry(List.of(plugin(" HTTP ", "/users/{username}/groups")), "", "");

            SourceRegistry.GroupDirectory groups = registry.byId("nc1").orElseThrow().groups();
            assertThat(groups.kind()).isEqualTo("http");
            assertThat(groups.path()).isEqualTo("/users/{username}/groups");
            assertThat(groups.groupsPointer()).isEqualTo("/ocs/data/groups");
            assertThat(groups.headers()).containsEntry("OCS-APIRequest", "true");
            assertThat(groups.prefix()).isEqualTo("GROUP_");
        }

        @Test
        void anEntryWithoutTheBlockHasNoDirectory() {
            SourceRegistry registry = registry(List.of(entry("fs1", "filesystem", "http://fs")), "", "");

            assertThat(registry.byId("fs1").orElseThrow().groups()).isNull();
        }

        @Test
        void refusesAnUnknownKind() {
            assertThatThrownBy(() -> registry(List.of(plugin("ldap", "/users/{username}")), "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("rag.sources[0] (nextcloud nc1)")
                    .hasMessageContaining("ldap");
        }

        @Test
        void refusesAPathWithoutTheUsername() {
            assertThatThrownBy(() -> registry(List.of(plugin("http", "/groups")), "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("{username}");
        }

        @Test
        void refusesAPointerThatIsNotAJsonPointer() {
            RagProperties.SourceProperties p = plugin("http", "/users/{username}");
            p.getGroups().setGroupsPointer("ocs.data.groups");

            assertThatThrownBy(() -> registry(List.of(p), "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JSON Pointer");
        }

        @Test
        void refusesAPrefixThatWouldTurnAGroupIntoAUser() {
            RagProperties.SourceProperties p = plugin("http", "/users/{username}");
            p.getGroups().setPrefix("nc_");

            assertThatThrownBy(() -> registry(List.of(p), "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GROUP_");
        }

        @Test
        void refusesTheBlockOnATypeWithABuiltInResolver() {
            RagProperties.SourceProperties p = plugin("http", "/users/{username}");
            p.setType("alfresco");

            assertThatThrownBy(() -> registry(List.of(p), "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("built-in group resolver");
        }
    }
}
