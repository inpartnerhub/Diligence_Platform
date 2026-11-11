package com.inpartnerhub.diligence.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.inpartnerhub.diligence.notification.EmailSender.EmailMessage;

/** #126: SMTP calls are bounded by timeouts; JavaMail's defaults wait forever. */
class SmtpTimeoutTest {

    private static final List<String> TIMEOUTS = List.of("mail.smtp.connectiontimeout", "mail.smtp.timeout",
            "mail.smtp.writetimeout");

    private final List<Socket> accepted = new ArrayList<>();
    private ServerSocket server;

    @AfterEach
    void close() throws Exception {
        for (var socket : accepted) {
            socket.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void applicationConfigurationSetsEveryTimeout() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        for (var key : TIMEOUTS) {
            assertThat(properties.getProperty("spring.mail.properties." + key)).as(key).isNotBlank();
        }
    }

    @Test
    void aStalledServerFailsTheSendWithinTheTimeout() throws Exception {
        server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try {
                while (!server.isClosed()) {
                    accepted.add(server.accept()); // accepts, never sends the SMTP greeting
                }
            } catch (Exception ignored) {
                // closed by the test
            }
        });
        var mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(server.getLocalPort());
        var javaMail = new Properties();
        TIMEOUTS.forEach(key -> javaMail.setProperty(key, "500"));
        mailSender.setJavaMailProperties(javaMail);
        var sender = new SmtpEmailSender(mailSender, "no-reply@x.test");

        var started = System.nanoTime();
        assertThatThrownBy(() -> sender.send(new EmailMessage("a@x.test", "S", "B", null)))
                .isInstanceOf(MailException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }
}
