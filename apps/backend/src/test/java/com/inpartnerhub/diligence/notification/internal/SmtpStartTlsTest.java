package com.inpartnerhub.diligence.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.inpartnerhub.diligence.notification.EmailSender.EmailMessage;

/** #127: with STARTTLS on, a server that does not offer it (or a stripped offer) must not get the mail in plaintext. */
class SmtpStartTlsTest {

    @Test
    void startTlsIsRequiredWheneverItIsEnabled() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        var enable = properties.getProperty("spring.mail.properties.mail.smtp.starttls.enable");
        assertThat(properties.getProperty("spring.mail.properties.mail.smtp.starttls.required")).isEqualTo(enable);
        assertThat(properties.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity"))
                .isEqualTo(enable);
        assertThat(properties.getProperty("spring.mail.properties.mail.smtp.ssl.protocols"))
                .isEqualTo("TLSv1.2 TLSv1.3");
    }

    @Test
    void aServerWithoutStartTlsNeverReceivesTheMessage() throws Exception {
        var receivedData = new AtomicBoolean();
        try (var server = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> serveWithoutStartTls(server, receivedData));
            var mailSender = new JavaMailSenderImpl();
            mailSender.setHost("127.0.0.1");
            mailSender.setPort(server.getLocalPort());
            var javaMail = new Properties();
            javaMail.setProperty("mail.smtp.starttls.enable", "true");
            javaMail.setProperty("mail.smtp.starttls.required", "true");
            javaMail.setProperty("mail.smtp.timeout", "5000");
            mailSender.setJavaMailProperties(javaMail);
            var sender = new SmtpEmailSender(mailSender, "no-reply@x.test");

            assertThatThrownBy(() -> sender.send(new EmailMessage("a@x.test", "S", "Body", null)))
                    .isInstanceOf(MailException.class)
                    .hasStackTraceContaining("STARTTLS");
        }
        assertThat(receivedData).isFalse();
    }

    /** A minimal SMTP server that answers EHLO without offering STARTTLS and accepts anything. */
    private static void serveWithoutStartTls(ServerSocket server, AtomicBoolean receivedData) {
        try (var socket = server.accept();
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                var out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII)) {
            out.print("220 stub ESMTP\r\n");
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                var command = line.toUpperCase(java.util.Locale.ROOT);
                if (command.startsWith("EHLO")) {
                    out.print("250-stub\r\n250 8BITMIME\r\n");
                } else if (command.startsWith("DATA")) {
                    receivedData.set(true);
                    out.print("354 go\r\n");
                } else if (command.startsWith("QUIT")) {
                    out.print("221 bye\r\n");
                    out.flush();
                    return;
                } else {
                    out.print("250 ok\r\n");
                }
                out.flush();
            }
        } catch (Exception ignored) {
            // the client hung up
        }
    }
}
