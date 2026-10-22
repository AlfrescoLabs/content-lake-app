package org.hyland.nuxeo.contentlake.live.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hyland.nuxeo.contentlake.client.NuxeoClient;
import org.hyland.nuxeo.contentlake.live.config.NuxeoLiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The startup warning for a Nuxeo source left on the default source id. */
@ExtendWith(MockitoExtension.class)
class NuxeoAuditListenerSourceIdWarningTest {

    @Mock
    private NuxeoClient nuxeoClient;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(NuxeoAuditListener.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void theDefaultSourceIdIsWarnedAboutOnce() {
        when(nuxeoClient.getSourceId()).thenReturn("local");
        NuxeoAuditListener listener = listener();

        listener.warnIfDefaultSourceId();
        listener.warnIfDefaultSourceId();

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().getFirst()).contains("NUXEO_SOURCE_ID", "'local'");
    }

    @Test
    void aConfiguredSourceIdIsNotWarnedAbout() {
        when(nuxeoClient.getSourceId()).thenReturn("prod-nuxeo");

        listener().warnIfDefaultSourceId();

        assertThat(warnings()).isEmpty();
    }

    private NuxeoAuditListener listener() {
        Clock clock = Clock.systemUTC();
        return new NuxeoAuditListener(null, null, null, nuxeoClient, null, new NuxeoLiveProperties(),
                new NuxeoAuditMetrics(new SimpleMeterRegistry(), clock), clock);
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("NUXEO_SOURCE_ID"))
                .toList();
    }
}
