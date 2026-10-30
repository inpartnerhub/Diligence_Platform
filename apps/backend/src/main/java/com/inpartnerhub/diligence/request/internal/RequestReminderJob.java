package com.inpartnerhub.diligence.request.internal;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Daily reminder run (PRD REQ-04). ShedLock makes sure only one replica sends reminders. */
@Component
class RequestReminderJob {

    private final RequestReminderService reminders;
    private final ZoneId zone;

    RequestReminderJob(RequestReminderService reminders, @Value("${diligence.request.reminder-zone}") ZoneId zone) {
        this.reminders = reminders;
        this.zone = zone;
    }

    @Scheduled(cron = "${diligence.request.reminder-cron}", zone = "${diligence.request.reminder-zone}")
    @SchedulerLock(name = "request-reminders", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void run() {
        reminders.remind(LocalDate.now(zone));
    }
}
