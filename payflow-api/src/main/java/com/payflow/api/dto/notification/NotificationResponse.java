package com.payflow.api.dto.notification;

import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationChannel;
import com.payflow.core.domain.notification.NotificationDeliveryStatus;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.core.domain.notification.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID eventId,
        String type,
        NotificationSeverity severity,
        UUID recipientUserId,
        String recipientRole,
        String title,
        String message,
        String refType,
        String refId,
        NotificationStatus status,
        NotificationChannel channel,
        NotificationDeliveryStatus deliveryStatus,
        Instant createdAt,
        Instant deliveredAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getEventId(),
                n.getType(),
                n.getSeverity(),
                n.getRecipientUser() != null ? n.getRecipientUser().getId() : null,
                n.getRecipientRole(),
                n.getTitle(),
                n.getMessage(),
                n.getRefType(),
                n.getRefId(),
                n.getStatus(),
                n.getChannel(),
                n.getDeliveryStatus(),
                n.getCreatedAt(),
                n.getDeliveredAt()
        );
    }
}
