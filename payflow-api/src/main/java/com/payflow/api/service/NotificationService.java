package com.payflow.api.service;

import com.payflow.api.dto.notification.NotificationResponse;
import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.core.domain.notification.NotificationStatus;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.exception.UnauthorizedAccessException;
import com.payflow.core.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getUserNotifications(UUID userId, boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = unreadOnly
                ? notificationRepository.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(userId, NotificationStatus.UNREAD, pageable)
                : notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(userId, pageable);
        return page.map(NotificationResponse::from);
    }

    @Transactional
    public NotificationResponse markNotificationRead(UUID notificationId, UUID userId, boolean isAdmin) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));

        boolean isRecipient = notification.getRecipientUser() != null && notification.getRecipientUser().getId().equals(userId);
        boolean isRoleRecipient = isAdmin && "ROLE_ADMIN".equals(notification.getRecipientRole());

        if (!isRecipient && !isRoleRecipient && !isAdmin) {
            throw new UnauthorizedAccessException("Not authorized to access or modify this notification");
        }

        notification.markRead();
        return NotificationResponse.from(notificationRepository.save(notification));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getAdminAlerts(NotificationSeverity severity,
                                                     String type,
                                                     NotificationStatus status,
                                                     Pageable pageable) {
        return notificationRepository.findFiltered(severity, type, status, pageable)
                .map(NotificationResponse::from);
    }
}
