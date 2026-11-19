package com.payflow.api.controller;

import com.payflow.api.dto.notification.NotificationResponse;
import com.payflow.api.service.NotificationService;
import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.core.domain.notification.NotificationStatus;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.exception.UnauthorizedAccessException;
import com.payflow.core.repository.NotificationRepository;
import com.payflow.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
public class NotificationIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User alice;
    private User bob;
    private User admin;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(new User("alice_notif", "alice@notif.test", "hash", UserRole.ROLE_USER));
        bob = userRepository.save(new User("bob_notif", "bob@notif.test", "hash", UserRole.ROLE_USER));
        admin = userRepository.save(new User("admin_notif", "admin@notif.test", "hash", UserRole.ROLE_ADMIN));
    }

    @Test
    @DisplayName("Users can retrieve their notifications with unread filtering")
    void testUserNotificationsAndUnreadFilter() {
        Notification n1 = new Notification(
                UUID.randomUUID(), "TEST_ALERT", NotificationSeverity.INFO,
                alice, null, "Alice Title 1", "Msg 1", "REF", "1"
        );
        Notification n2 = new Notification(
                UUID.randomUUID(), "TEST_ALERT", NotificationSeverity.HIGH,
                alice, null, "Alice Title 2", "Msg 2", "REF", "2"
        );
        n2.markRead();

        Notification nBob = new Notification(
                UUID.randomUUID(), "TEST_ALERT", NotificationSeverity.INFO,
                bob, null, "Bob Title", "Msg Bob", "REF", "3"
        );

        notificationRepository.save(n1);
        notificationRepository.save(n2);
        notificationRepository.save(nBob);

        // All Alice notifications
        Page<NotificationResponse> allAlice = notificationService.getUserNotifications(
                alice.getId(), false, PageRequest.of(0, 10));
        assertThat(allAlice.getContent()).hasSize(2);

        // Unread only Alice notifications
        Page<NotificationResponse> unreadAlice = notificationService.getUserNotifications(
                alice.getId(), true, PageRequest.of(0, 10));
        assertThat(unreadAlice.getContent()).hasSize(1);
        assertThat(unreadAlice.getContent().get(0).title()).isEqualTo("Alice Title 1");
    }

    @Test
    @DisplayName("Users can mark their own notifications as read")
    void testMarkAsRead() {
        Notification n1 = notificationRepository.save(new Notification(
                UUID.randomUUID(), "TEST_ALERT", NotificationSeverity.INFO,
                alice, null, "Title", "Msg", "REF", "1"
        ));

        NotificationResponse response = notificationService.markNotificationRead(n1.getId(), alice.getId(), false);

        assertThat(response.status()).isEqualTo(NotificationStatus.READ);
        assertThat(notificationRepository.findById(n1.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.READ);
    }

    @Test
    @DisplayName("Users cannot read or modify another user's notifications")
    void testCrossUserAccessDenied() {
        Notification n1 = notificationRepository.save(new Notification(
                UUID.randomUUID(), "TEST_ALERT", NotificationSeverity.INFO,
                alice, null, "Alice Private Title", "Msg", "REF", "1"
        ));

        // Bob tries to mark Alice's notification as read
        assertThrows(UnauthorizedAccessException.class, () ->
                notificationService.markNotificationRead(n1.getId(), bob.getId(), false)
        );
    }

    @Test
    @DisplayName("Admin can retrieve filtered alerts")
    void testAdminAlertsFiltering() {
        notificationRepository.save(new Notification(
                UUID.randomUUID(), "FRAUD_PROOF", NotificationSeverity.CRITICAL,
                null, "ROLE_ADMIN", "Admin Alert 1", "Msg", "PROOF", "1"
        ));
        notificationRepository.save(new Notification(
                UUID.randomUUID(), "UNCOVERED", NotificationSeverity.HIGH,
                null, "ROLE_ADMIN", "Admin Alert 2", "Msg", "RECEIPT", "2"
        ));
        notificationRepository.save(new Notification(
                UUID.randomUUID(), "INFO_ALERT", NotificationSeverity.INFO,
                alice, null, "User Alert", "Msg", "USER", "3"
        ));

        Page<NotificationResponse> criticals = notificationService.getAdminAlerts(
                NotificationSeverity.CRITICAL, null, null, PageRequest.of(0, 10));
        assertThat(criticals.getContent()).hasSize(1);
        assertThat(criticals.getContent().get(0).title()).isEqualTo("Admin Alert 1");
    }

    @Test
    @DisplayName("Deduplication: Unique constraint prevents duplicate notifications")
    void testNotificationDeduplicationConstraint() {
        UUID eventId = UUID.randomUUID();

        Notification n1 = new Notification(
                eventId, "OFFLINE_FRAUD_PROOF_DETECTED", NotificationSeverity.CRITICAL,
                alice, "ROLE_ADMIN", "Title 1", "Msg 1", "REF", "1"
        );
        notificationRepository.saveAndFlush(n1);

        Notification nDuplicate = new Notification(
                eventId, "OFFLINE_FRAUD_PROOF_DETECTED", NotificationSeverity.CRITICAL,
                alice, "ROLE_ADMIN", "Title 2", "Msg 2", "REF", "1"
        );

        // Unique constraint violates on duplicate (event_id, type, recipient_user_id, recipient_role)
        assertThrows(DataIntegrityViolationException.class, () ->
                notificationRepository.saveAndFlush(nDuplicate)
        );
    }
}
