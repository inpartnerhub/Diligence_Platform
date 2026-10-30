package com.inpartnerhub.diligence.notification;

/** Port for transactional e-mail (SMTP locally via Mailpit; SES/SendGrid SMTP relay in deployed environments). */
public interface EmailSender {

    void send(EmailMessage message);

    record EmailMessage(String to, String subject, String plainTextBody, String htmlBody) {
    }
}
