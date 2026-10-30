/**
 * Notifications (PRD ADM-05, REQ-04): transactional e-mail and in-app notifications with per-user preferences.
 * Other modules call {@link com.inpartnerhub.diligence.notification.Notifier} from their background listeners; each
 * notification is stored in the recipient's inbox and e-mailed according to the recipient's preferences per
 * {@link com.inpartnerhub.diligence.notification.NotificationType}.
 * Account e-mails (invitation, password reset, OTP setup) are sent by Keycloak itself.
 */
@ApplicationModule(displayName = "Notification", allowedDependencies = { "shared", "security", "admin" })
package com.inpartnerhub.diligence.notification;

import org.springframework.modulith.ApplicationModule;
