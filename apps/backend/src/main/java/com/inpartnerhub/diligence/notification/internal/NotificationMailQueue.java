package com.inpartnerhub.diligence.notification.internal;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.inpartnerhub.diligence.notification.EmailSender;
import com.inpartnerhub.diligence.notification.EmailSender.EmailMessage;

/**
 * Sends notification e-mails off the caller's thread, once its transaction has committed (#126). No database
 * connection waits on the SMTP server, and a rolled-back change sends no e-mail. A few dedicated threads send them
 * one after another, so a burst (the daily reminders) does not open many SMTP connections at once. E-mail is best
 * effort, as before: failures are logged and the in-app notification stays. Not an {@code Executor} bean on purpose,
 * so Spring's {@code applicationTaskExecutor} (used by the asynchronous module listeners) is left alone.
 */
@Component
class NotificationMailQueue implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NotificationMailQueue.class);

    private final EmailSender email;
    private final Executor executor;
    private final ThreadPoolTaskExecutor pool;

    @Autowired
    NotificationMailQueue(EmailSender email, @Value("${diligence.notification.mail-threads:2}") int threads) {
        this.email = email;
        this.pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(threads);
        pool.setMaxPoolSize(threads);
        pool.setThreadNamePrefix("notification-mail-");
        // Queued e-mails are still sent on a graceful shutdown, within the shutdown phase timeout.
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(25);
        pool.initialize();
        this.executor = pool;
    }

    /** For tests: runs the delivery on the given executor. */
    NotificationMailQueue(EmailSender email, Executor executor) {
        this.email = email;
        this.executor = executor;
        this.pool = null;
    }

    /** Queues the e-mail after the current transaction commits, or at once without a transaction. */
    void sendAfterCommit(EmailMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executor.execute(() -> sendQuietly(message));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> sendQuietly(message));
            }
        });
    }

    private void sendQuietly(EmailMessage message) {
        try {
            email.send(message);
        } catch (RuntimeException ex) {
            log.warn("Notification e-mail could not be sent: {}", ex.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (pool != null) {
            pool.shutdown();
        }
    }
}
