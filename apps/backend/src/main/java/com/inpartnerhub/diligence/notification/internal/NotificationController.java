package com.inpartnerhub.diligence.notification.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.notification.NotificationType;
import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping(ApiPaths.V1 + "/me")
@Tag(name = "Notifications")
class NotificationController {

    record UnreadCount(long unread) {
    }

    record PreferenceDto(@NotNull NotificationType type, boolean emailEnabled, boolean inAppEnabled) {
    }

    record UpdatePreferencesRequest(@NotNull @Size(max = 50) List<@Valid PreferenceDto> preferences) {
    }

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/notifications")
    @Operation(summary = "The caller's in-app notifications, newest first — ADM-05")
    PageResponse<NotificationService.InboxEntry> inbox(@RequestParam(defaultValue = "false") boolean unreadOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        var page = notifications.inbox(unreadOnly, pageable);
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    @GetMapping("/notifications/unread-count")
    @Operation(summary = "Number of unread notifications (polled by the header bell) — ADM-05")
    UnreadCount unreadCount() {
        return new UnreadCount(notifications.unreadCount());
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one notification as read")
    void markRead(@PathVariable UUID id) {
        notifications.markRead(id);
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark all notifications as read")
    void markAllRead() {
        notifications.markAllRead();
    }

    @GetMapping("/notification-preferences")
    @Operation(summary = "E-mail and in-app choices per notification type — ADM-05")
    List<PreferenceDto> preferences() {
        return notifications.preferences().stream().map(NotificationController::dto).toList();
    }

    @PutMapping("/notification-preferences")
    @Operation(summary = "Change e-mail and in-app choices per notification type — ADM-05")
    List<PreferenceDto> updatePreferences(@Valid @RequestBody UpdatePreferencesRequest request) {
        var changes = request.preferences().stream()
                .map(p -> new NotificationService.Preference(p.type(), p.emailEnabled(), p.inAppEnabled())).toList();
        return notifications.updatePreferences(changes).stream().map(NotificationController::dto).toList();
    }

    private static PreferenceDto dto(NotificationService.Preference p) {
        return new PreferenceDto(p.type(), p.emailEnabled(), p.inAppEnabled());
    }
}
