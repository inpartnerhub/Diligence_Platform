package com.inpartnerhub.diligence.notification.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.notification.EmailSender;

import jakarta.mail.MessagingException;

@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, @Value("${diligence.notification.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        var mime = mailSender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            if (message.htmlBody() == null) {
                helper.setText(message.plainTextBody());
            } else {
                helper.setText(message.plainTextBody(), message.htmlBody());
            }
        } catch (MessagingException ex) {
            throw new IllegalArgumentException("Invalid e-mail message", ex);
        }
        mailSender.send(mime);
    }
}
