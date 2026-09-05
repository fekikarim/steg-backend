package tn.steg.backend.notification.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.notification.application.NotificationService;
import tn.steg.backend.notification.application.dto.NotificationResponse;

import java.util.Map;
import java.util.UUID;

/**
 * REST adapter for the current user's notifications (Phase A10).
 * Every query is scoped to the caller's own IN_APP deliveries server-side —
 * a user can never see another user's notifications.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "My notifications, read receipts (IN_APP channel)")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List my notifications (paginated, optional unread-only filter)")
    public ResponseEntity<Page<NotificationResponse>> listMine(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(notificationService.listMine(actor, unreadOnly, pageable));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Count my unread notifications")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(Map.of("unreadCount", notificationService.unreadCount(actor)));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark one of my notifications as read")
    public ResponseEntity<NotificationResponse> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(notificationService.markRead(id, actor));
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark all my notifications as read")
    public ResponseEntity<Map<String, Long>> markAllRead(@AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(Map.of("markedRead", notificationService.markAllRead(actor)));
    }
}
