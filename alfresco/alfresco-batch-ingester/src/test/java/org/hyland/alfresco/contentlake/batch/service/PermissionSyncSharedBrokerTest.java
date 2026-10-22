package org.hyland.alfresco.contentlake.batch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.hyland.alfresco.contentlake.batch.config.JmsConfig;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncRequest;
import org.hyland.alfresco.contentlake.batch.model.PermissionSyncResult;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.AbstractMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two batch ingesters, one per repository, consuming the ACL change queue on one broker.
 *
 * <p>Each context is the listener as the batch ingester wires it: transacted session, deferred start, and
 * the message selector set once the repository id is known. Each repository's changes must reach its own
 * ingester only; a message that names no repository is applied once, by either.</p>
 */
class PermissionSyncSharedBrokerTest {

    private static final String BROKER_URL = "vm://acl-shared?create=false";
    private static final String QUEUE = "contentlake.acl.changed";

    private BrokerService broker;
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

    @BeforeEach
    void startBroker() throws Exception {
        broker = new BrokerService();
        broker.setBrokerName("acl-shared");
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setAdvisorySupport(false);
        broker.start();
        broker.waitUntilStarted();
    }

    @AfterEach
    void stopAll() throws Exception {
        contexts.forEach(AnnotationConfigApplicationContext::close);
        broker.stop();
        broker.waitUntilStopped();
    }

    @Test
    void eachRepositorysAclChangeIsAppliedByItsOwnIngester() {
        RecordingSyncService ingesterA = startIngester(new FixedRepositoryClient("repo-a", 0));
        RecordingSyncService ingesterB = startIngester(new FixedRepositoryClient("repo-b", 0));

        for (int i = 0; i < 5; i++) {
            send("repo-a", "a-" + i);
            send("repo-b", "b-" + i);
        }
        send(null, "legacy");

        await().untilAsserted(() -> {
            assertThat(ingesterA.nodeIds()).contains("a-0", "a-1", "a-2", "a-3", "a-4");
            assertThat(ingesterB.nodeIds()).contains("b-0", "b-1", "b-2", "b-3", "b-4");
            assertThat(concat(ingesterA.nodeIds(), ingesterB.nodeIds())).contains("legacy");
        });
        assertThat(ingesterA.nodeIds()).noneMatch(id -> id.startsWith("b-"));
        assertThat(ingesterB.nodeIds()).noneMatch(id -> id.startsWith("a-"));
        assertThat(concat(ingesterA.nodeIds(), ingesterB.nodeIds())).containsOnlyOnce("legacy");
        // Rollback and redelivery would also end here, after several round trips; the selector is what
        // keeps another repository's message from being dispatched to this ingester at all.
        assertThat(selectorOf(contexts.getFirst()))
                .isEqualTo("repositoryId IS NULL OR repositoryId = 'repo-a'");
    }

    /** A repository that is not up yet delays the listener; its messages wait on the queue. */
    @Test
    void listenerStartsOnceTheRepositoryIdBecomesAvailable() {
        FixedRepositoryClient lateRepository = new FixedRepositoryClient("repo-a", 2);
        send("repo-a", "a-0");

        RecordingSyncService ingester = startIngester(lateRepository);

        await().untilAsserted(() -> assertThat(ingester.nodeIds()).containsExactly("a-0"));
        assertThat(lateRepository.calls()).isGreaterThan(2);
    }

    private RecordingSyncService startIngester(AlfrescoClient alfrescoClient) {
        RecordingSyncService syncService = new RecordingSyncService();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("alfrescoClient", alfrescoClient);
        context.getBeanFactory().registerSingleton("permissionSyncService", syncService);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("content.lake.permission.sync.start-retry-interval-ms", "50")));
        context.register(IngesterJmsConfig.class);
        context.refresh();
        contexts.add(context);
        return syncService;
    }

    private static void send(String repositoryId, String nodeId) {
        JmsTemplate template = new JmsTemplate(new ActiveMQConnectionFactory(BROKER_URL));
        String repository = repositoryId == null ? "" : "\"repositoryId\":\"" + repositoryId + "\",";
        template.send(QUEUE, session -> {
            TextMessage message = session.createTextMessage("{\"eventType\":\"contentlake.acl.changed\","
                    + "\"sourceType\":\"alfresco\"," + repository + "\"nodeId\":\"" + nodeId + "\","
                    + "\"recursive\":false}");
            if (repositoryId != null) {
                message.setStringProperty(PermissionSyncQueueListener.REPOSITORY_ID_PROPERTY, repositoryId);
            }
            return message;
        });
    }

    private static String selectorOf(AnnotationConfigApplicationContext context) {
        return ((AbstractMessageListenerContainer) context.getBean(JmsListenerEndpointRegistry.class)
                .getListenerContainer(PermissionSyncQueueListener.LISTENER_ID)).getMessageSelector();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** What {@code JmsConfig} sets up for the batch ingester, against the embedded broker. */
    @Configuration
    @EnableJms
    @Import({PermissionSyncQueueListener.class, PermissionSyncListenerStarter.class})
    static class IngesterJmsConfig {

        @Bean
        ConnectionFactory connectionFactory() {
            return new ActiveMQConnectionFactory(BROKER_URL);
        }

        @Bean(name = JmsConfig.PERMISSION_SYNC_CONTAINER_FACTORY)
        DefaultJmsListenerContainerFactory permissionSyncListenerContainerFactory(ConnectionFactory connectionFactory) {
            DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            factory.setPubSubDomain(false);
            factory.setSessionTransacted(true);
            factory.setAutoStartup(false);
            return factory;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    /** Records the node ids it is asked to reconcile. */
    private static final class RecordingSyncService extends PermissionSyncService {

        private final List<String> nodeIds = new CopyOnWriteArrayList<>();

        private RecordingSyncService() {
            super(null, null, null, null);
        }

        @Override
        public PermissionSyncResult syncPermissions(PermissionSyncRequest request) {
            nodeIds.addAll(request.getNodeIds());
            return null;
        }

        List<String> nodeIds() {
            return List.copyOf(nodeIds);
        }
    }

    /** A repository whose Discovery call fails {@code failures} times before it answers. */
    private static final class FixedRepositoryClient extends AlfrescoClient {

        private final String repositoryId;
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        private FixedRepositoryClient(String repositoryId, int failures) {
            super(null, null);
            this.repositoryId = repositoryId;
            this.failures = failures;
        }

        @Override
        public String getRepositoryId() {
            if (calls.incrementAndGet() <= failures) {
                throw new IllegalStateException("repository not up");
            }
            return repositoryId;
        }

        int calls() {
            return calls.get();
        }
    }
}
