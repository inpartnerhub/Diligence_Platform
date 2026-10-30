package com.inpartnerhub.diligence.notification.internal;

import java.util.Map;

import com.inpartnerhub.diligence.notification.EmailSender.EmailMessage;
import com.inpartnerhub.diligence.notification.NotificationType;

/**
 * E-mail texts per notification type, in Indonesian followed by English (the platform's two languages, PRD Bab 7);
 * in-app texts are rendered by the frontend from the type and parameters.
 */
final class NotificationMessages {

    private NotificationMessages() {
    }

    static EmailMessage email(String to, NotificationType type, Map<String, String> params, String url) {
        var p = new Params(params);
        var project = p.get("projectCode");
        String subject;
        String indonesian;
        String english;
        switch (type) {
            case REQUEST_RELEASED -> {
                subject = "[%s] %s permintaan dokumen baru / new document requests".formatted(project, p.get("count"));
                indonesian = "Tim due diligence meminta %s dokumen baru (gelombang %s) untuk proyek %s."
                        .formatted(p.get("count"), p.get("wave"), project);
                english = "The due diligence team requested %s new items (wave %s) for project %s."
                        .formatted(p.get("count"), p.get("wave"), project);
            }
            case REQUEST_SUBMITTED -> {
                subject = "[%s] Dokumen diterima / documents delivered: %s".formatted(project, p.get("itemNumber"));
                indonesian = "Pihak target mengirim dokumen untuk permintaan %s \"%s\" (status: %s)."
                        .formatted(p.get("itemNumber"), p.get("description"), p.get("status"));
                english = "The target delivered documents for request %s \"%s\" (status: %s)."
                        .formatted(p.get("itemNumber"), p.get("description"), p.get("status"));
            }
            case REQUEST_REVIEWED -> {
                var rejected = "REJECTED".equals(p.get("status"));
                subject = "[%s] Permintaan %s %s / request %s".formatted(project, p.get("itemNumber"),
                        rejected ? "ditolak" : "ditutup", rejected ? "rejected" : "closed");
                indonesian = rejected
                        ? "Dokumen untuk permintaan %s \"%s\" belum memadai: %s. Mohon kirim ulang."
                                .formatted(p.get("itemNumber"), p.get("description"), p.get("reason"))
                        : "Permintaan %s \"%s\" telah ditutup.".formatted(p.get("itemNumber"), p.get("description"));
                english = rejected
                        ? "The documents for request %s \"%s\" are not sufficient: %s. Please resubmit."
                                .formatted(p.get("itemNumber"), p.get("description"), p.get("reason"))
                        : "Request %s \"%s\" was closed.".formatted(p.get("itemNumber"), p.get("description"));
            }
            case REQUEST_COMMENTED -> {
                subject = "[%s] Komentar baru / new comment: %s".formatted(project, p.get("itemNumber"));
                indonesian = "Ada komentar baru pada permintaan %s \"%s\"."
                        .formatted(p.get("itemNumber"), p.get("description"));
                english = "There is a new comment on request %s \"%s\"."
                        .formatted(p.get("itemNumber"), p.get("description"));
            }
            case REQUEST_DUE_REMINDER -> {
                subject = "[%s] Pengingat permintaan dokumen / request reminder".formatted(project);
                indonesian = "Proyek %s: %s permintaan jatuh tempo dalam waktu dekat dan %s sudah lewat tenggat."
                        .formatted(project, p.get("dueSoon"), p.get("overdue"));
                english = "Project %s: %s requests are due soon and %s are overdue."
                        .formatted(project, p.get("dueSoon"), p.get("overdue"));
            }
            case REQUEST_OVERDUE_DIGEST -> {
                subject = "[%s] %s permintaan lewat tenggat / overdue requests".formatted(project, p.get("overdue"));
                indonesian = "Proyek %s memiliki %s permintaan dokumen yang lewat tenggat."
                        .formatted(project, p.get("overdue"));
                english = "Project %s has %s overdue document requests.".formatted(project, p.get("overdue"));
            }
            case FINDING_SUBMITTED -> {
                subject = "[%s] Temuan menunggu review / finding awaits review: %s".formatted(project,
                        p.get("findingNumber"));
                indonesian = "Temuan %s \"%s\" diajukan untuk review.".formatted(p.get("findingNumber"),
                        p.get("title"));
                english = "Finding %s \"%s\" was submitted for review.".formatted(p.get("findingNumber"),
                        p.get("title"));
            }
            case FINDING_RETURNED -> {
                subject = "[%s] Temuan dikembalikan / finding returned: %s".formatted(project,
                        p.get("findingNumber"));
                indonesian = "Temuan %s \"%s\" dikembalikan ke draf: %s".formatted(p.get("findingNumber"),
                        p.get("title"), p.get("reason"));
                english = "Finding %s \"%s\" was returned to draft: %s".formatted(p.get("findingNumber"),
                        p.get("title"), p.get("reason"));
            }
            case FINDING_APPROVED -> {
                subject = "[%s] Temuan disetujui / finding approved: %s".formatted(project, p.get("findingNumber"));
                indonesian = "Temuan %s \"%s\" disetujui dan siap dirilis oleh Partner."
                        .formatted(p.get("findingNumber"), p.get("title"));
                english = "Finding %s \"%s\" was approved and is ready for release by a partner."
                        .formatted(p.get("findingNumber"), p.get("title"));
            }
            case FINDING_RELEASED -> {
                subject = "[%s] Temuan baru dirilis / new finding released: %s".formatted(project,
                        p.get("findingNumber"));
                indonesian = "Temuan %s \"%s\" telah dirilis untuk Anda.".formatted(p.get("findingNumber"),
                        p.get("title"));
                english = "Finding %s \"%s\" has been released to you.".formatted(p.get("findingNumber"),
                        p.get("title"));
            }
            case FINDING_ESCALATED -> {
                subject = "[%s] Eskalasi risiko %s / %s risk escalation: %s".formatted(project, p.get("level"),
                        p.get("level"), p.get("findingNumber"));
                indonesian = "Temuan %s \"%s\" dinilai berisiko %s (skor %s). Mohon ditindaklanjuti."
                        .formatted(p.get("findingNumber"), p.get("title"), p.get("level"), p.get("score"));
                english = "Finding %s \"%s\" was rated %s risk (score %s). Please follow up."
                        .formatted(p.get("findingNumber"), p.get("title"), p.get("level"), p.get("score"));
            }
            case QUESTION_PENDING_APPROVAL -> {
                subject = "[%s] Pertanyaan menunggu persetujuan / question awaits approval: %s".formatted(project,
                        p.get("questionNumber"));
                indonesian = "Pertanyaan %s menunggu persetujuan sebelum dikirim ke pihak target: \"%s\""
                        .formatted(p.get("questionNumber"), p.get("excerpt"));
                english = "Question %s awaits approval before it is sent to the target: \"%s\""
                        .formatted(p.get("questionNumber"), p.get("excerpt"));
            }
            case QUESTION_RETURNED -> {
                subject = "[%s] Pertanyaan dikembalikan / question returned: %s".formatted(project,
                        p.get("questionNumber"));
                indonesian = "Pertanyaan %s dikembalikan: %s".formatted(p.get("questionNumber"), p.get("feedback"));
                english = "Question %s was returned: %s".formatted(p.get("questionNumber"), p.get("feedback"));
            }
            case QUESTION_SENT -> {
                subject = "[%s] Pertanyaan baru / new question: %s".formatted(project, p.get("questionNumber"));
                indonesian = "Tim due diligence mengajukan pertanyaan %s: \"%s\"".formatted(p.get("questionNumber"),
                        p.get("excerpt"));
                english = "The due diligence team asks question %s: \"%s\"".formatted(p.get("questionNumber"),
                        p.get("excerpt"));
            }
            case QUESTION_ANSWERED -> {
                subject = "[%s] Pertanyaan dijawab / question answered: %s".formatted(project, p.get("questionNumber"));
                indonesian = "Pertanyaan %s telah dijawab oleh pihak target.".formatted(p.get("questionNumber"));
                english = "Question %s was answered by the target.".formatted(p.get("questionNumber"));
            }
            case QUESTION_CLOSED -> {
                subject = "[%s] Jawaban tersedia / answer available: %s".formatted(project, p.get("questionNumber"));
                indonesian = "Jawaban atas pertanyaan Anda %s sudah tersedia.".formatted(p.get("questionNumber"));
                english = "The answer to your question %s is available.".formatted(p.get("questionNumber"));
            }
            case REPORT_ISSUED -> {
                subject = "[%s] Laporan diterbitkan / report issued: %s".formatted(project, p.get("title"));
                indonesian = "Laporan \"%s\" (versi %s) telah diterbitkan dan dapat diunduh."
                        .formatted(p.get("title"), p.get("version"));
                english = "Report \"%s\" (version %s) was issued and can be downloaded."
                        .formatted(p.get("title"), p.get("version"));
            }
            default -> throw new IllegalArgumentException("No e-mail text for " + type);
        }
        var body = indonesian + "\n\n" + english + "\n\n" + url
                + "\n\n— Diligence Platform (e-mail otomatis, jangan dibalas / automated e-mail, do not reply)";
        return new EmailMessage(to, subject, body, null);
    }

    private record Params(Map<String, String> values) {

        String get(String key) {
            return values.getOrDefault(key, "-");
        }
    }
}
