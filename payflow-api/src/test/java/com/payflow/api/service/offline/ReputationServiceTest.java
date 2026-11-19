package com.payflow.api.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.config.ReputationProperties;
import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.dto.reputation.ReputationOverrideRequest;
import com.payflow.api.dto.reputation.ReputationResponse;
import com.payflow.api.metrics.PayFlowMetricsService;
import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.FraudProof;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.offline.RejectionCategory;
import com.payflow.core.domain.reputation.ReputationEventType;
import com.payflow.core.domain.reputation.ReputationTier;
import com.payflow.core.domain.reputation.UserReputation;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.ReputationLimitExceededException;
import com.payflow.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReputationServiceTest {

    @Mock
    private UserReputationRepository userReputationRepository;

    @Mock
    private ReputationEventRepository reputationEventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OfflineTransactionRepository offlineTransactionRepository;

    @Mock
    private OfflineRejectedReceiptRepository rejectedReceiptRepository;

    @Mock
    private FraudProofRepository fraudProofRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PayFlowMetricsService metricsService;

    private ReputationProperties properties;
    private ObjectMapper objectMapper;
    private ReputationService reputationService;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;

    @BeforeEach
    void setUp() {
        properties = new ReputationProperties();
        properties.setStartingScore(100);
        properties.setCleanReceiptPoints(5);
        properties.setDailyGainCap(50);
        properties.setAllowanceCompletionBonus(20);
        properties.setRejectionPenalty(10);
        properties.setFraudPenalty(100);
        properties.setHysteresis(15);
        properties.setMinAccountAgeDays(7);
        properties.setMinDistinctCounterparties(3);

        objectMapper = new ObjectMapper().findAndRegisterModules();

        reputationService = new ReputationService(
                userReputationRepository,
                reputationEventRepository,
                userRepository,
                offlineTransactionRepository,
                rejectedReceiptRepository,
                fraudProofRepository,
                outboxEventRepository,
                properties,
                metricsService,
                objectMapper
        );

        senderUser = new User("alice", "alice@test.com", "hash", UserRole.ROLE_USER);
        senderUser.setId(UUID.randomUUID());
        // Set created at 10 days ago
        java.lang.reflect.Field createdAtField;
        try {
            createdAtField = User.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(senderUser, Instant.now().minus(10, ChronoUnit.DAYS));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        receiverUser = new User("bob", "bob@test.com", "hash", UserRole.ROLE_USER);
        receiverUser.setId(UUID.randomUUID());

        senderWallet = new Wallet(senderUser, "USD", BigDecimal.valueOf(1000));
        senderWallet.setId(UUID.randomUUID());

        receiverWallet = new Wallet(receiverUser, "USD", BigDecimal.valueOf(1000));
        receiverWallet.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Clean settlement awards points up to daily cap")
    void testCleanSettlementAwardsPointsAndCaps() {
        UserReputation rep = new UserReputation(senderUser, 100);
        when(userReputationRepository.findByUserIdForUpdate(senderUser.getId())).thenReturn(Optional.of(rep));
        when(reputationEventRepository.sumScoreDeltaByUserIdAndEventTypeSince(eq(senderUser.getId()), eq(ReputationEventType.CLEAN_SETTLEMENT), any()))
                .thenReturn(48); // Already earned 48 points today (cap is 50)

        OfflineReceiptDto receipt = new OfflineReceiptDto(
                UUID.randomUUID(), UUID.randomUUID(), senderWallet.getId(), receiverWallet.getId(),
                new BigDecimal("10.0000"), "USD", 1L, "nonce", "sig", Instant.now()
        );

        reputationService.recordCleanSettlement(receipt, senderWallet, receiverWallet);

        // Can only gain 2 points (50 - 48 = 2) instead of standard 5
        assertThat(rep.getScore()).isEqualTo(102);
        assertThat(rep.getCleanSettledCount()).isEqualTo(1);
        verify(reputationEventRepository).save(argThat(event ->
                event.getEventType() == ReputationEventType.CLEAN_SETTLEMENT &&
                event.getScoreDelta() == 2 &&
                event.getScoreAfter() == 102
        ));
    }

    @Test
    @DisplayName("Anti-gaming: Wash trading between own wallets awards zero points")
    void testWashTradingAwardsZeroPoints() {
        Wallet sendersSecondWallet = new Wallet(senderUser, "USD", BigDecimal.valueOf(1000));
        sendersSecondWallet.setId(UUID.randomUUID());

        UserReputation rep = new UserReputation(senderUser, 100);
        when(userReputationRepository.findByUserIdForUpdate(senderUser.getId())).thenReturn(Optional.of(rep));

        OfflineReceiptDto receipt = new OfflineReceiptDto(
                UUID.randomUUID(), UUID.randomUUID(), senderWallet.getId(), sendersSecondWallet.getId(),
                new BigDecimal("25.0000"), "USD", 1L, "nonce", "sig", Instant.now()
        );

        reputationService.recordCleanSettlement(receipt, senderWallet, sendersSecondWallet);

        // Zero points awarded
        assertThat(rep.getScore()).isEqualTo(100);
        assertThat(rep.getCleanSettledCount()).isEqualTo(1);
        verify(reputationEventRepository).save(argThat(event ->
                event.getEventType() == ReputationEventType.CLEAN_SETTLEMENT &&
                event.getScoreDelta() == 0 &&
                event.getReason().contains("wash trading")
        ));
    }

    @Test
    @DisplayName("Anti-gaming: Promotion above NEW requires account age and distinct counterparties")
    void testPromotionRequirements() {
        UserReputation rep = new UserReputation(senderUser, 250); // score >= 200 (BRONZE score)
        rep.setTier(ReputationTier.NEW);
        when(userReputationRepository.findByUserIdForUpdate(senderUser.getId())).thenReturn(Optional.of(rep));
        when(offlineTransactionRepository.countDistinctReceiversBySenderUserId(senderUser.getId())).thenReturn(2L); // Only 2 counterparties (< 3)

        OfflineReceiptDto receipt = new OfflineReceiptDto(
                UUID.randomUUID(), UUID.randomUUID(), senderWallet.getId(), receiverWallet.getId(),
                new BigDecimal("5.0000"), "USD", 1L, "nonce", "sig", Instant.now()
        );

        reputationService.recordCleanSettlement(receipt, senderWallet, receiverWallet);

        // Stays in NEW because counterparties requirement (3) is not satisfied
        assertThat(rep.getTier()).isEqualTo(ReputationTier.NEW);

        // Now satisfy counterparties requirement (3)
        when(offlineTransactionRepository.countDistinctReceiversBySenderUserId(senderUser.getId())).thenReturn(3L);
        reputationService.recordCleanSettlement(receipt, senderWallet, receiverWallet);

        // Promoted to BRONZE!
        assertThat(rep.getTier()).isEqualTo(ReputationTier.BRONZE);
        verify(metricsService).recordReputationTierChange(ReputationTier.NEW, ReputationTier.BRONZE);
    }

    @Test
    @DisplayName("Hysteresis prevents demotion thrash on score fluctuations")
    void testHysteresisPreventsDemotionThrash() {
        UserReputation rep = new UserReputation(senderUser, 205);
        rep.setTier(ReputationTier.BRONZE); // Bronze threshold is 200, hysteresis is 15 -> demote only if < 185
        when(userReputationRepository.findByUserIdForUpdate(senderUser.getId())).thenReturn(Optional.of(rep));
        when(offlineTransactionRepository.countDistinctReceiversBySenderUserId(senderUser.getId())).thenReturn(5L);

        // Penalize 10 points: 205 - 10 = 195. 195 is below 200, but above 185!
        reputationService.recordRejectionPenalty(senderUser.getId(), "tx-1", "Invalid sig", RejectionCategory.SENDER_ATTRIBUTABLE);

        assertThat(rep.getScore()).isEqualTo(195);
        // Does NOT demote to NEW thanks to 15-point hysteresis band (threshold 185)
        assertThat(rep.getTier()).isEqualTo(ReputationTier.BRONZE);

        // Penalize another 15 points: 195 - 10 = 185 (still >= 185)
        reputationService.recordRejectionPenalty(senderUser.getId(), "tx-2", "Limit exceeded", RejectionCategory.SENDER_ATTRIBUTABLE);
        assertThat(rep.getScore()).isEqualTo(185);
        assertThat(rep.getTier()).isEqualTo(ReputationTier.BRONZE);

        // One more penalty: 185 - 10 = 175 (< 185) -> Demotes to NEW!
        reputationService.recordRejectionPenalty(senderUser.getId(), "tx-3", "Limit exceeded", RejectionCategory.SENDER_ATTRIBUTABLE);
        assertThat(rep.getScore()).isEqualTo(175);
        assertThat(rep.getTier()).isEqualTo(ReputationTier.NEW);
        verify(metricsService).recordReputationTierChange(ReputationTier.BRONZE, ReputationTier.NEW);
    }

    @Test
    @DisplayName("Non-attributable rejections cost zero points; sender-attributable cost penalty")
    void testRejectionPenalties() {
        UserReputation rep = new UserReputation(senderUser, 100);
        when(userReputationRepository.findByUserIdForUpdate(senderUser.getId())).thenReturn(Optional.of(rep));

        // System or Receiver related rejection -> 0 cost
        reputationService.recordRejectionPenalty(senderUser.getId(), "tx-rec", "Receiver inactive", RejectionCategory.RECEIVER_RELATED);
        reputationService.recordRejectionPenalty(senderUser.getId(), "tx-sys", "Database timeout", RejectionCategory.SYSTEM_ERROR);
        assertThat(rep.getScore()).isEqualTo(100);
        assertThat(rep.getRejectedCount()).isEqualTo(0);

        // Sender-attributable rejection -> 10 penalty
        reputationService.recordRejectionPenalty(senderUser.getId(), "tx-send", "Invalid counter signature", RejectionCategory.SENDER_ATTRIBUTABLE);
        assertThat(rep.getScore()).isEqualTo(90);
        assertThat(rep.getRejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Fraud proof detection penalizes 100 points, sets RESTRICTED tier immediately")
    void testFraudProofDetectionRestriction() {
        UserReputation rep = new UserReputation(senderUser, 150);
        rep.setTier(ReputationTier.NEW);
        when(userReputationRepository.findByUserIdForUpdate(senderUser.getId())).thenReturn(Optional.of(rep));

        UUID proofId = UUID.randomUUID();
        reputationService.recordFraudDetected(senderUser.getId(), proofId);

        assertThat(rep.getScore()).isEqualTo(50);
        assertThat(rep.isRestricted()).isTrue();
        assertThat(rep.getTier()).isEqualTo(ReputationTier.RESTRICTED);
        assertThat(rep.getFraudProofsDetected()).isEqualTo(1);

        verify(metricsService).recordReputationTierChange(ReputationTier.NEW, ReputationTier.RESTRICTED);
        verify(outboxEventRepository).save(argThat(event ->
                event.getAggregateType().equals("USER_REPUTATION") &&
                event.getEventType().equals("REPUTATION_TIER_CHANGED")
        ));
    }

    @Test
    @DisplayName("Dismissed fraud proof restores score and un-restricts user")
    void testDismissedProofRestoresScore() {
        UserReputation rep = new UserReputation(senderUser, 50);
        rep.setTier(ReputationTier.RESTRICTED);
        rep.setRestricted(true);
        rep.setFraudProofsDetected(1);
        when(userReputationRepository.findByUserIdForUpdate(senderUser.getId())).thenReturn(Optional.of(rep));
        when(fraudProofRepository.findByAllowanceUserId(senderUser.getId())).thenReturn(Collections.emptyList());

        UUID proofId = UUID.randomUUID();
        reputationService.restoreDismissedProof(senderUser.getId(), proofId);

        assertThat(rep.getScore()).isEqualTo(150);
        assertThat(rep.isRestricted()).isFalse();
        assertThat(rep.getTier()).isEqualTo(ReputationTier.NEW);
        verify(reputationEventRepository).save(argThat(event ->
                event.getEventType() == ReputationEventType.FRAUD_PROOF_DISMISSED_RESTORATION &&
                event.getScoreDelta() == 100
        ));
    }

    @Test
    @DisplayName("Check allowance allocation limits and restriction")
    void testCheckAllowanceLimits() {
        UserReputation rep = new UserReputation(senderUser, 100);
        rep.setTier(ReputationTier.NEW); // NEW: maxAllowance 500, maxSingleTx 200
        when(userReputationRepository.findByUserId(senderUser.getId())).thenReturn(Optional.of(rep));

        // Valid within tier limits
        reputationService.checkAllowanceRequest(senderUser.getId(), new BigDecimal("400.0000"), new BigDecimal("150.0000"));

        // Exceeds max allowance
        assertThrows(ReputationLimitExceededException.class, () ->
                reputationService.checkAllowanceRequest(senderUser.getId(), new BigDecimal("600.0000"), new BigDecimal("100.0000")));

        // Exceeds max single tx
        assertThrows(ReputationLimitExceededException.class, () ->
                reputationService.checkAllowanceRequest(senderUser.getId(), new BigDecimal("400.0000"), new BigDecimal("250.0000")));

        // Restricted user
        rep.setRestricted(true);
        assertThrows(ReputationLimitExceededException.class, () ->
                reputationService.checkAllowanceRequest(senderUser.getId(), new BigDecimal("50.0000"), new BigDecimal("10.0000")));
    }
}
