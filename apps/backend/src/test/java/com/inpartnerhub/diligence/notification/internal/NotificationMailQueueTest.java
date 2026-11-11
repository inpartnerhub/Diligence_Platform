package com.inpartnerhub.diligence.notification.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.inpartnerhub.diligence.notification.EmailSender;
import com.inpartnerhub.diligence.notification.EmailSender.EmailMessage;

/** #126: notification e-mails leave only after the transaction committed, never while it holds a connection. */
class NotificationMailQueueTest {

    private final EmailSender email = mock(EmailSender.class);
    private final NotificationMailQueue queue = new NotificationMailQueue(email, Runnable::run);
    private final EmailMessage message = new EmailMessage("a@x.test", "Subject", "Body", null);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendsAfterTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        queue.sendAfterCommit(message);
        verify(email, never()).send(any());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(email).send(message);
    }

    @Test
    void sendsNothingWhenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();

        queue.sendAfterCommit(message);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(email, never()).send(any());
    }

    @Test
    void sendsAtOnceWithoutATransactionAndSwallowsFailures() {
        doThrow(new IllegalStateException("smtp down")).when(email).send(message);

        queue.sendAfterCommit(message);

        verify(email).send(message);
    }

    @Test
    void theDefaultQueueSendsOnItsOwnThreads() throws Exception {
        var sent = new java.util.concurrent.CountDownLatch(1);
        var threadName = new java.util.concurrent.atomic.AtomicReference<String>();
        EmailSender recording = m -> {
            threadName.set(Thread.currentThread().getName());
            sent.countDown();
        };
        var pooled = new NotificationMailQueue(recording, 1);
        try {
            pooled.sendAfterCommit(message);
            org.assertj.core.api.Assertions.assertThat(sent.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThat(threadName.get()).startsWith("notification-mail-");
        } finally {
            pooled.destroy();
        }
    }
}
