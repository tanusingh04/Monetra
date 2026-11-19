package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.notification.NotificationResponse;
import com.payflow.api.security.UserPrincipal;
import com.payflow.api.service.NotificationService;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.core.domain.notification.NotificationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Tag(name = "Notifications & Fraud Alerts", description = "Endpoints for user notifications and administrative fraud alerts")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/notifications")
    @Operation(summary = "Fetch current user's notifications (paginated, with optional unread filter)")
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getMyNotifications(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<NotificationResponse> response = notificationService.getUserNotifications(
                currentUser.getId(), unreadOnly, pageable);
        return ResponseEntity.ok(ApiResponse.success("Notifications retrieved successfully", response));
    }

    @PostMapping("/api/notifications/{id}/read")
    @Operation(summary = "Mark a notification as read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        NotificationResponse response = notificationService.markNotificationRead(id, currentUser.getId(), isAdmin);
        return ResponseEntity.ok(ApiResponse.success("Notification marked as read", response));
    }

    @GetMapping("/api/admin/alerts")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch system fraud alerts filtered by severity, type, and status (Admin only)")
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getAdminAlerts(
            @RequestParam(required = false) NotificationSeverity severity,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<NotificationResponse> response = notificationService.getAdminAlerts(severity, type, status, pageable);
        return ResponseEntity.ok(ApiResponse.success("Admin alerts retrieved successfully", response));
    }
}
