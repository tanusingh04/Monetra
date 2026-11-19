package com.payflow.core.domain.notification;

import com.payflow.core.domain.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notifications", uniqueConstraints = {
        @UniqueConstraint(name = "uq_notifications_dedup", columnNames = {"event_id", "type", "recipient_user_id", "recipient_role"})
})
public class Notification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 50)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationSeverity severity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id")
    private User recipientUser;

    @Column(name = "recipient_role", length = 30)
    private String recipientRole;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "ref_type", length = 50)
    private String refType;

    @Column(name = "ref_id", length = 100)
    private String refId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status = NotificationStatus.UNREAD;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationChannel channel = NotificationChannel.IN_APP;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    private NotificationDeliveryStatus deliveryStatus = NotificationDeliveryStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    public Notification() {}

    public Notification(UUID eventId,
                        String type,
                        NotificationSeverity severity,
                        User recipientUser,
                        String recipientRole,
                        String title,
                        String message,
                        String refType,
                        String refId) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.type = type;
        this.severity = severity;
        this.recipientUser = recipientUser;
        this.recipientRole = recipientRole;
        this.title = title;
        this.message = message;
        this.refType = refType;
        this.refId = refId;
        this.status = NotificationStatus.UNREAD;
        this.channel = NotificationChannel.IN_APP;
        this.deliveryStatus = NotificationDeliveryStatus.PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public void markRead() {
        this.status = NotificationStatus.READ;
    }

    public void markDelivered() {
        this.deliveryStatus = NotificationDeliveryStatus.DELIVERED;
        this.deliveredAt = Instant.now();
    }

    public void markFailed() {
        this.deliveryStatus = NotificationDeliveryStatus.FAILED;
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public NotificationSeverity getSeverity() {
        return severity;
    }

    public User getRecipientUser() {
        return recipientUser;
    }

    public String getRecipientRole() {
        return recipientRole;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getRefType() {
        return refType;
    }

    public String getRefId() {
        return refId;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public void setChannel(NotificationChannel channel) {
        this.channel = channel;
    }

    public NotificationDeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Notification that = (Notification) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
