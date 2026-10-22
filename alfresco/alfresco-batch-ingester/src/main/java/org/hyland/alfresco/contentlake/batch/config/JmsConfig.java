package org.hyland.alfresco.contentlake.batch.config;

import jakarta.jms.ConnectionFactory;
import org.springframework.boot.jms.autoconfigure.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

@Configuration
@EnableJms
public class JmsConfig {

    /** Container factory of the ACL change listener. */
    public static final String PERMISSION_SYNC_CONTAINER_FACTORY = "permissionSyncListenerContainerFactory";

    @Bean
    public JmsListenerContainerFactory<DefaultMessageListenerContainer> jmsListenerContainerFactory(
            ConnectionFactory connectionFactory,
            DefaultJmsListenerContainerFactoryConfigurer configurer
    ) {
        return transactedQueueFactory(connectionFactory, configurer);
    }

    /**
     * Same as the default factory, except that its containers do not start with the context.
     * {@code PermissionSyncListenerStarter} starts the ACL change listener once the repository id for its
     * message selector is known.
     */
    @Bean(name = PERMISSION_SYNC_CONTAINER_FACTORY)
    public JmsListenerContainerFactory<DefaultMessageListenerContainer> permissionSyncListenerContainerFactory(
            ConnectionFactory connectionFactory,
            DefaultJmsListenerContainerFactoryConfigurer configurer
    ) {
        DefaultJmsListenerContainerFactory factory = transactedQueueFactory(connectionFactory, configurer);
        factory.setAutoStartup(false);
        return factory;
    }

    private static DefaultJmsListenerContainerFactory transactedQueueFactory(
            ConnectionFactory connectionFactory,
            DefaultJmsListenerContainerFactoryConfigurer configurer
    ) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setPubSubDomain(false);
        factory.setSessionTransacted(true);
        return factory;
    }
}
