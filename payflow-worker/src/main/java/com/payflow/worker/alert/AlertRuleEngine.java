package com.payflow.worker.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.repository.OfflineAllowanceRepository;
import com.payflow.core.repository.UserRepository;
import com.payflow.core.repository.WalletRepository;
import com.payflow.worker.metrics.PayFlowWorkerMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AlertRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleEngine.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final OfflineAllowanceRepository allowanceRepository;
    private final ObjectMapper objectMapper;
    private final PayFlowWorkerMetricsService metricsService;

    public AlertRuleEngine(UserRepository userRepository,
                           WalletRepository walletRepository,
                           OfflineAllowanceRepository allowanceRepository,
                           ObjectMapper objectMapper,
                           @Autowired(required = false) PayFlowWorkerMetricsService metricsService) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.allowanceRepository = allowanceRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    public List<Notification> evaluateAlertRules(String eventType, String payloadJson) {
        List<Notification> notifications = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(payloadJson);

            switch (eventType) {
                case "OFFLINE_FRAUD_PROOF_DETECTED" -> processFraudProofDetected(root, notifications);
                case "OFFLINE_RECEIPT_PROTECTED" -> processReceiptProtected(root, notifications);
                case "OFFLINE_RECEIPT_UNCOVERED" -> processReceiptUncovered(root, notifications);
                case "REPUTATION_TIER_CHANGED" -> processReputationTierChanged(root, notifications);
                case "LEDGER_ROOT_VERIFICATION_FAILED" -> processLedgerVerificationFailed(root, notifications);
                default -> log.debug("No alert rules triggered for event type: {}", eventType);
            }
        } catch (Exception ex) {
            log.error("Failed to evaluate alert rules for event {}: {}", eventType, ex.getMessage(), ex);
        }

        for (Notification n : notifications) {
            if (metricsService != null) {
                metricsService.incrementAlertCreated(n.getType(), n.getSeverity().name());
            }
        }

        return notifications;
    }

    private void processFraudProofDetected(JsonNode node, List<Notification> notifications) {
        UUID proofId = UUID.fromString(node.get("proofId").asText());
        UUID allowanceId = UUID.fromString(node.get("allowanceId").asText());
        String deviceId = node.get("deviceId").asText();
        long counter = node.get("counter").asLong();

        // 1. CRITICAL to ADMIN
        Notification adminAlert = new Notification(
                proofId,
                "OFFLINE_FRAUD_PROOF_DETECTED",
                NotificationSeverity.CRITICAL,
                null,
                "ROLE_ADMIN",
                "CRITICAL: Offline Double-Spend Fraud Proof Detected",
                String.format("Equivocation detected on counter %d by device %s for allowance %s. Device and allowance revoked.",
                        counter, deviceId, allowanceId),
                "FRAUD_PROOF",
                proofId.toString()
        );
        notifications.add(adminAlert);

        // 2. HIGH to Device Owner
        Optional<OfflineAllowance> allowanceOpt = allowanceRepository.findById(allowanceId);
        if (allowanceOpt.isPresent()) {
            User owner = allowanceOpt.get().getUser();
            Notification userAlert = new Notification(
                    proofId,
                    "OFFLINE_FRAUD_PROOF_DETECTED",
                    NotificationSeverity.HIGH,
                    owner,
                    null,
                    "Security Notice: Offline Device Equivocation Detected",
                    String.format("Conflicting signatures were detected on counter %d from your device %s. Your offline allowance and device have been suspended.",
                            counter, maskDeviceId(deviceId)),
                    "FRAUD_PROOF",
                    proofId.toString()
            );
            notifications.add(userAlert);
        }
    }

    private void processReceiptProtected(JsonNode node, List<Notification> notifications) {
        UUID proofId = UUID.fromString(node.get("proofId").asText());
        UUID receiptTxId = UUID.fromString(node.get("receiptTxId").asText());
        UUID receiverWalletId = UUID.fromString(node.get("receiverWalletId").asText());
        BigDecimal amount = new BigDecimal(node.get("amount").asText());

        // INFO to Receiver (Privacy: NO sender deviceId, NO public key, NO raw payload)
        Optional<Wallet> receiverWallet = walletRepository.findById(receiverWalletId);
        if (receiverWallet.isPresent()) {
            Notification receiverNotice = new Notification(
                    proofId,
                    "OFFLINE_RECEIPT_PROTECTED",
                    NotificationSeverity.INFO,
                    receiverWallet.get().getUser(),
                    null,
                    "Payment Honored: Offline Settlement Protected",
                    String.format("Your offline payment receipt (%s) for %s %s was honored and settled from sender reserve funds under receiver protection.",
                            receiptTxId, amount, receiverWallet.get().getCurrency()),
                    "RECEIPT",
                    receiptTxId.toString()
            );
            notifications.add(receiverNotice);
        }
    }

    private void processReceiptUncovered(JsonNode node, List<Notification> notifications) {
        UUID proofId = UUID.fromString(node.get("proofId").asText());
        UUID receiptTxId = UUID.fromString(node.get("receiptTxId").asText());
        UUID receiverWalletId = UUID.fromString(node.get("receiverWalletId").asText());
        BigDecimal uncoveredAmount = new BigDecimal(node.get("uncoveredAmount").asText());

        Optional<Wallet> receiverWallet = walletRepository.findById(receiverWalletId);

        // 1. HIGH to Receiver (Privacy-masked)
        if (receiverWallet.isPresent()) {
            Notification receiverAlert = new Notification(
                    proofId,
                    "OFFLINE_RECEIPT_UNCOVERED",
                    NotificationSeverity.HIGH,
                    receiverWallet.get().getUser(),
                    null,
                    "Payment Uncovered: Offline Receipt Settlement Incomplete",
                    String.format("Offline receipt (%s) could not be fully covered by sender reserve. Uncovered balance: %s %s.",
                            receiptTxId, uncoveredAmount, receiverWallet.get().getCurrency()),
                    "RECEIPT",
                    receiptTxId.toString()
            );
            notifications.add(receiverAlert);
        }

        // 2. HIGH to ADMIN
        Notification adminAlert = new Notification(
                proofId,
                "OFFLINE_RECEIPT_UNCOVERED",
                NotificationSeverity.HIGH,
                null,
                "ROLE_ADMIN",
                "Uncovered Offline Receipt Settlement",
                String.format("Fraud-associated receipt %s could not be covered from reserve. Uncovered amount: %s.",
                        receiptTxId, uncoveredAmount),
                "RECEIPT",
                receiptTxId.toString()
        );
        notifications.add(adminAlert);
    }

    private void processReputationTierChanged(JsonNode node, List<Notification> notifications) {
        UUID eventId = UUID.fromString(node.get("eventId").asText());
        UUID userId = UUID.fromString(node.get("userId").asText());
        String prevTier = node.get("previousTier").asText();
        String newTier = node.get("newTier").asText();
        int score = node.get("score").asInt();

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) return;

        User user = userOpt.get();

        if ("RESTRICTED".equals(newTier)) {
            Notification restrictedNotice = new Notification(
                    eventId,
                    "REPUTATION_TIER_CHANGED",
                    NotificationSeverity.HIGH,
                    user,
                    null,
                    "Account Notice: Offline Privileges Suspended",
                    String.format("Your account reputation tier has been set to RESTRICTED (score: %d). Offline allowance requests are currently suspended.", score),
                    "USER_REPUTATION",
                    userId.toString()
            );
            notifications.add(restrictedNotice);
        } else {
            Notification tierNotice = new Notification(
                    eventId,
                    "REPUTATION_TIER_CHANGED",
                    NotificationSeverity.MEDIUM,
                    user,
                    null,
                    "Reputation Tier Update",
                    String.format("Your reputation tier has transitioned from %s to %s (current score: %d).", prevTier, newTier, score),
                    "USER_REPUTATION",
                    userId.toString()
            );
            notifications.add(tierNotice);
        }
    }

    private void processLedgerVerificationFailed(JsonNode node, List<Notification> notifications) {
        UUID eventId = node.has("eventId") ? UUID.fromString(node.get("eventId").asText()) : UUID.randomUUID();
        String details = node.has("details") ? node.get("details").asText() : "Root mismatch or chain linkage break detected";

        Notification adminAlert = new Notification(
                eventId,
                "LEDGER_ROOT_VERIFICATION_FAILED",
                NotificationSeverity.CRITICAL,
                null,
                "ROLE_ADMIN",
                "CRITICAL: Ledger Root Verification Failed",
                "Cryptographic discrepancy detected in Merkle tree ledger verification: " + details,
                "LEDGER_ROOT",
                eventId.toString()
        );
        notifications.add(adminAlert);
    }

    /**
     * Privacy masking: Masks sensitive sender device IDs to protect sender privacy from receivers.
     */
    public static String maskDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return "device ***";
        }
        if (deviceId.length() <= 4) {
            return "device ***";
        }
        return "device ***" + deviceId.substring(deviceId.length() - 4);
    }
}
