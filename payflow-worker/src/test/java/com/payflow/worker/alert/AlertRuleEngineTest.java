package com.payflow.worker.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.repository.OfflineAllowanceRepository;
import com.payflow.core.repository.UserRepository;
import com.payflow.core.repository.WalletRepository;
import com.payflow.worker.metrics.PayFlowWorkerMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AlertRuleEngineTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private OfflineAllowanceRepository allowanceRepository;

    @Mock
    private PayFlowWorkerMetricsService metricsService;

    private ObjectMapper objectMapper;
    private AlertRuleEngine alertRuleEngine;

    private User alice;
    private User bob;
    private Wallet bobWallet;
    private OfflineAllowance aliceAllowance;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        alertRuleEngine = new AlertRuleEngine(
                userRepository, walletRepository, allowanceRepository, objectMapper, metricsService
        );

        alice = new User("alice", "alice@test.com", "hash", UserRole.ROLE_USER);
        alice.setId(UUID.randomUUID());

        bob = new User("bob", "bob@test.com", "hash", UserRole.ROLE_USER);
        bob.setId(UUID.randomUUID());

        bobWallet = new Wallet(bob, "INR", new BigDecimal("5000.0000"));
        bobWallet.setId(UUID.randomUUID());

        aliceAllowance = mock(OfflineAllowance.class);
        when(aliceAllowance.getUser()).thenReturn(alice);
    }

    @Test
    @DisplayName("Fraud proof detected triggers CRITICAL to ADMIN and HIGH to device owner")
    void testFraudProofDetectedAlertRules() throws Exception {
        UUID proofId = UUID.randomUUID();
        UUID allowanceId = UUID.randomUUID();
        when(allowanceRepository.findById(allowanceId)).thenReturn(Optional.of(aliceAllowance));

        String payload = String.format("""
                {
                    "proofId": "%s",
                    "allowanceId": "%s",
                    "deviceId": "pixel-8-pro-fraud-01",
                    "counter": 5,
                    "firstTxId": "%s",
                    "secondTxId": "%s",
                    "secondReceiptSettled": false,
                    "protectedAmount": 0,
                    "uncoveredAmount": 250.0000
                }
                """, proofId, allowanceId, UUID.randomUUID(), UUID.randomUUID());

        List<Notification> alerts = alertRuleEngine.evaluateAlertRules("OFFLINE_FRAUD_PROOF_DETECTED", payload);

        assertThat(alerts).hasSize(2);

        Notification adminAlert = alerts.stream().filter(n -> "ROLE_ADMIN".equals(n.getRecipientRole())).findFirst().orElseThrow();
        assertThat(adminAlert.getSeverity()).isEqualTo(NotificationSeverity.CRITICAL);
        assertThat(adminAlert.getTitle()).contains("CRITICAL");

        Notification ownerAlert = alerts.stream().filter(n -> n.getRecipientUser() != null).findFirst().orElseThrow();
        assertThat(ownerAlert.getSeverity()).isEqualTo(NotificationSeverity.HIGH);
        assertThat(ownerAlert.getRecipientUser().getId()).isEqualTo(alice.getId());
        assertThat(ownerAlert.getMessage()).contains("device ***d-01"); // masked device
    }

    @Test
    @DisplayName("Receiver protected receipt triggers INFO notification with sender privacy preserved")
    void testReceiverProtectedReceiptAlert() {
        UUID proofId = UUID.randomUUID();
        UUID receiptTxId = UUID.randomUUID();
        when(walletRepository.findById(bobWallet.getId())).thenReturn(Optional.of(bobWallet));

        String payload = String.format("""
                {
                    "proofId": "%s",
                    "receiptTxId": "%s",
                    "receiverWalletId": "%s",
                    "senderWalletId": "%s",
                    "amount": 300.0000,
                    "protectionStatus": "SETTLED_FROM_RESERVE"
                }
                """, proofId, receiptTxId, bobWallet.getId(), UUID.randomUUID());

        List<Notification> alerts = alertRuleEngine.evaluateAlertRules("OFFLINE_RECEIPT_PROTECTED", payload);

        assertThat(alerts).hasSize(1);
        Notification notice = alerts.get(0);
        assertThat(notice.getSeverity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(notice.getRecipientUser().getId()).isEqualTo(bob.getId());
        assertThat(notice.getTitle()).contains("Payment Honored");

        // Privacy check: Receiver must not see raw deviceId, public key, or raw signatures
        assertThat(notice.getMessage()).doesNotContain("deviceId");
        assertThat(notice.getMessage()).doesNotContain("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE");
    }

    @Test
    @DisplayName("Uncovered receipt triggers HIGH to Receiver and HIGH to Admin")
    void testUncoveredReceiptAlerts() {
        UUID proofId = UUID.randomUUID();
        UUID receiptTxId = UUID.randomUUID();
        when(walletRepository.findById(bobWallet.getId())).thenReturn(Optional.of(bobWallet));

        String payload = String.format("""
                {
                    "proofId": "%s",
                    "receiptTxId": "%s",
                    "receiverWalletId": "%s",
                    "senderWalletId": "%s",
                    "uncoveredAmount": "150.0000"
                }
                """, proofId, receiptTxId, bobWallet.getId(), UUID.randomUUID());

        List<Notification> alerts = alertRuleEngine.evaluateAlertRules("OFFLINE_RECEIPT_UNCOVERED", payload);

        assertThat(alerts).hasSize(2);
        Notification receiverAlert = alerts.stream().filter(n -> n.getRecipientUser() != null).findFirst().orElseThrow();
        assertThat(receiverAlert.getSeverity()).isEqualTo(NotificationSeverity.HIGH);
        assertThat(receiverAlert.getMessage()).contains("150.0000");

        Notification adminAlert = alerts.stream().filter(n -> "ROLE_ADMIN".equals(n.getRecipientRole())).findFirst().orElseThrow();
        assertThat(adminAlert.getSeverity()).isEqualTo(NotificationSeverity.HIGH);
    }

    @Test
    @DisplayName("Reputation tier transition to RESTRICTED triggers HIGH notification to user")
    void testReputationRestrictedAlert() {
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));

        String payload = String.format("""
                {
                    "eventId": "%s",
                    "userId": "%s",
                    "previousTier": "NEW",
                    "newTier": "RESTRICTED",
                    "score": 0
                }
                """, UUID.randomUUID(), alice.getId());

        List<Notification> alerts = alertRuleEngine.evaluateAlertRules("REPUTATION_TIER_CHANGED", payload);

        assertThat(alerts).hasSize(1);
        Notification alert = alerts.get(0);
        assertThat(alert.getSeverity()).isEqualTo(NotificationSeverity.HIGH);
        assertThat(alert.getRecipientUser().getId()).isEqualTo(alice.getId());
        assertThat(alert.getTitle()).contains("Suspended");
    }

    @Test
    @DisplayName("Privacy masking helper masks device identifiers")
    void testDevicePrivacyMasking() {
        assertThat(AlertRuleEngine.maskDeviceId("pixel-8-pro-fraud-test")).isEqualTo("device ***test");
        assertThat(AlertRuleEngine.maskDeviceId("abc")).isEqualTo("device ***");
        assertThat(AlertRuleEngine.maskDeviceId(null)).isEqualTo("device ***");
    }
}
