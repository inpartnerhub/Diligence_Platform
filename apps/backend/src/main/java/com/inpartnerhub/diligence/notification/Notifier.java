package com.inpartnerhub.diligence.notification;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delivers a notification to users by e-mail and in-app, each according to the recipient's preferences (PRD ADM-05).
 * Called from background listeners (system context); inactive or unknown recipients are skipped.
 */
public interface Notifier {

    void notify(Notification notification);

    /**
     * @param params  values for the message texts (see {@link NotificationType}); shown to the recipient as-is
     * @param link    application path to open, e.g. {@code /projects/{id}/requests?item=…}
     */
    record Notification(Set<UUID> recipients, NotificationType type, UUID projectId, Map<String, String> params,
            String link) {

        public Notification {
            recipients = Set.copyOf(recipients);
            params = Map.copyOf(params);
        }
    }
}
