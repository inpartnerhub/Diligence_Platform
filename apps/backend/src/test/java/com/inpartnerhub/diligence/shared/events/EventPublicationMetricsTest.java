package com.inpartnerhub.diligence.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventPublicationMetricsTest {

    @Test
    void shortensListenerIdsToClassAndMethod() {
        assertThat(EventPublicationMetrics.listener("com.inpartnerhub.diligence.dataroom.internal.SearchIndexer.on("
                + "com.inpartnerhub.diligence.dataroom.internal.SearchIndexRequested)")).isEqualTo("SearchIndexer.on");
        assertThat(EventPublicationMetrics.listener("org.springframework.modulith.events.support."
                + "EventExternalizerModuleListener.externalize(java.lang.Object)"))
                .isEqualTo("EventExternalizerModuleListener.externalize");
        assertThat(EventPublicationMetrics.listener("com.example.Outer$Notifications.on(com.example.Event)"))
                .isEqualTo("Notifications.on");
        assertThat(EventPublicationMetrics.listener("unexpected")).isEqualTo("unexpected");
    }

    @Test
    void defaultsRetryForAboutTwoHours() {
        var defaults = new EventRetryProperties(null, null, null, null);

        assertThat(defaults.interval().multipliedBy(defaults.maxAttempts())).hasHours(2);
        assertThat(defaults.batchSize()).isEqualTo(100);
    }
}
