package com.payflow.core.repository;

import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.core.domain.notification.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(UUID userId, NotificationStatus status, Pageable pageable);

    Page<Notification> findByRecipientRoleOrderByCreatedAtDesc(String role, Pageable pageable);

    Page<Notification> findByRecipientRoleAndStatusOrderByCreatedAtDesc(String role, NotificationStatus status, Pageable pageable);

    long countByRecipientUserIdAndStatus(UUID userId, NotificationStatus status);

    @Query("SELECT n FROM Notification n WHERE " +
           "(:severity IS NULL OR n.severity = :severity) AND " +
           "(:type IS NULL OR n.type = :type) AND " +
           "(:status IS NULL OR n.status = :status) " +
           "ORDER BY n.createdAt DESC")
    Page<Notification> findFiltered(@Param("severity") NotificationSeverity severity,
                                    @Param("type") String type,
                                    @Param("status") NotificationStatus status,
                                    Pageable pageable);

    boolean existsByEventIdAndTypeAndRecipientUserId(UUID eventId, String type, UUID recipientUserId);
}
