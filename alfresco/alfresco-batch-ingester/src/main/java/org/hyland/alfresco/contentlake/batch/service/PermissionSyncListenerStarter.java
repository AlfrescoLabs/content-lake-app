package org.hyland.alfresco.contentlake.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.hyland.alfresco.contentlake.client.AlfrescoClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Starts the ACL change listener once this ingester knows its repository id.
 *
 * <p>The listener's message selector has to name the repository, and the id comes from the Discovery API,
 * which may not answer while the repository is still starting. Resolving it on the startup thread would
 * make the ingester fail to start whenever Alfresco is late, so a background thread retries until the id
 * is known, then sets {@link PermissionSyncQueueListener#selectorFor(String)} on the container and starts
 * it. Messages wait on the queue meanwhile; nothing is lost.</p>
 */
@Slf4j
@Component
public class PermissionSyncListenerStarter implements SmartLifecycle {

    private final JmsListenerEndpointRegistry registry;
    private final AlfrescoClient alfrescoClient;
    private final long retryIntervalMs;

    private volatile Thread worker;
    private volatile boolean running;

    public PermissionSyncListenerStarter(
            JmsListenerEndpointRegistry registry,
            AlfrescoClient alfrescoClient,
            @Value("${content.lake.permission.sync.start-retry-interval-ms:5000}") long retryIntervalMs
    ) {
        this.registry = registry;
        this.alfrescoClient = alfrescoClient;
        this.retryIntervalMs = retryIntervalMs;
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofPlatform()
                .name("permission-sync-listener-starter")
                .daemon(true)
                .start(this::resolveAndStart);
    }

    @Override
    public void stop() {
        running = false;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void resolveAndStart() {
        String repositoryId = resolveRepositoryId();
        if (repositoryId == null) {
            return;
        }
        MessageListenerContainer container = registry.getListenerContainer(PermissionSyncQueueListener.LISTENER_ID);
        if (container == null) {
            log.warn("No listener container {}; ACL change messages will not be consumed",
                    PermissionSyncQueueListener.LISTENER_ID);
            return;
        }
        String selector = PermissionSyncQueueListener.selectorFor(repositoryId);
        if (container instanceof AbstractMessageListenerContainer listenerContainer) {
            listenerContainer.setMessageSelector(selector);
        } else {
            log.warn("Listener container {} does not accept a message selector; another repository's ACL "
                    + "messages on a shared broker will be rolled back for redelivery",
                    PermissionSyncQueueListener.LISTENER_ID);
        }
        if (running) {
            container.start();
            log.info("ACL change listener started for repository {} with selector [{}]", repositoryId, selector);
        }
    }

    private String resolveRepositoryId() {
        while (running) {
            try {
                String repositoryId = alfrescoClient.getRepositoryId();
                if (repositoryId != null && !repositoryId.isBlank()) {
                    return repositoryId;
                }
                log.warn("Repository returned no id; retrying in {} ms", retryIntervalMs);
            } catch (Exception e) {
                log.warn("Could not read the repository id to start the ACL change listener ({}); retrying in {} ms",
                        e.getMessage(), retryIntervalMs);
            }
            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
