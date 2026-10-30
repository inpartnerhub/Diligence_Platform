/**
 * Notifications (PRD ADM-05, REQ-04): transactional e-mail and in-app notifications with per-user preferences.
 * Sprint 1–2 provides the e-mail port and preference storage; reminders and in-app delivery follow in sprint 9–10.
 * Account e-mails (invitation, password reset, OTP setup) are sent by Keycloak itself.
 */
@ApplicationModule(displayName = "Notification", allowedDependencies = "shared")
package com.inpartnerhub.diligence.notification;

import org.springframework.modulith.ApplicationModule;
