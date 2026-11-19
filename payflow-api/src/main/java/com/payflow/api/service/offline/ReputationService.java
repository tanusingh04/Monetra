package com.payflow.api.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.config.ReputationProperties;
import com.payflow.api.dto.offline.OfflineReceiptDto;
import com.payflow.api.dto.reputation.*;
import com.payflow.api.metrics.PayFlowMetricsService;
import com.payflow.core.domain.offline.FraudProofStatus;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.offline.RejectionCategory;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.reputation.ReputationEvent;
import com.payflow.core.domain.reputation.ReputationEventType;
import com.payflow.core.domain.reputation.ReputationTier;
import com.payflow.core.domain.reputation.UserReputation;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.event.ReputationTierChangedEvent;
import com.payflow.core.exception.ReputationLimitExceededException;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReputationService {

    private static final Logger log = LoggerFactory.getLogger(ReputationService.class);

    private final UserReputationRepository userReputationRepository;
    private final ReputationEventRepository reputationEventRepository;
    private final UserRepository userRepository;
    private final OfflineTransactionRepository offlineTransactionRepository;
    private final OfflineRejectedReceiptRepository rejectedReceiptRepository;
    private final FraudProofRepository fraudProofRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ReputationProperties properties;
    private final PayFlowMetricsService metricsService;
    private final ObjectMapper objectMapper;

    public ReputationService(UserReputationRepository userReputationRepository,
                             ReputationEventRepository reputationEventRepository,
                             UserRepository userRepository,
                             OfflineTransactionRepository offlineTransactionRepository,
                             OfflineRejectedReceiptRepository rejectedReceiptRepository,
                             FraudProofRepository fraudProofRepository,
                             OutboxEventRepository outboxEventRepository,
                             ReputationProperties properties,
                             @Autowired(required = false) PayFlowMetricsService metricsService,
                             ObjectMapper objectMapper) {
        this.userReputationRepository = userReputationRepository;
        this.reputationEventRepository = reputationEventRepository;
        this.userRepository = userRepository;
        this.offlineTransactionRepository = offlineTransactionRepository;
        this.rejectedReceiptRepository = rejectedReceiptRepository;
        this.fraudProofRepository = fraudProofRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UserReputation getOrCreateReputation(UUID userId) {
        Optional<UserReputation> existing = userReputationRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        int startScore = properties.getStartingScore();
        UserReputation reputation = new UserReputation(user, startScore);
        reputation = userReputationRepository.save(reputation);

        ReputationEvent event = new ReputationEvent(
                user,
                ReputationEventType.INITIAL_ENROLLMENT,
                startScore,
                startScore,
                "Initial reputation profile created with starting score of " + startScore,
                userId.toString()
        );
        reputationEventRepository.save(event);

        log.info("Initialized reputation profile for user {} with score {}", userId, startScore);
        return reputation;
    }

    /**
     * Validates an offline allowance request against user reputation tier limits.
     * Throws ReputationLimitExceededException (HTTP 422) if rejected.
     */
    @Transactional(readOnly = true)
    public void checkAllowanceRequest(UUID userId, BigDecimal requestedAmount, BigDecimal maxSingleTxAmount) {
        UserReputation rep = userReputationRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
                    return new UserReputation(user, properties.getStartingScore());
                });

        if (rep.isRestricted() || rep.getTier() == ReputationTier.RESTRICTED) {
            throw new ReputationLimitExceededException("User is currently RESTRICTED from requesting offline allowances due to fraud or administrative sanctions.");
        }

        ReputationProperties.TierLimit limit = properties.getEffectiveLimit(rep.getTier());

        if (requestedAmount.compareTo(limit.maxAllowance()) > 0) {
            throw new ReputationLimitExceededException(String.format(
                    "Requested allowance %s exceeds maximum allowance limit of %s for tier %s",
                    requestedAmount, limit.maxAllowance(), rep.getTier().name()));
        }

        if (maxSingleTxAmount.compareTo(limit.maxSingleTx()) > 0) {
            throw new ReputationLimitExceededException(String.format(
                    "Requested single transaction limit %s exceeds maximum single transaction limit of %s for tier %s",
                    maxSingleTxAmount, limit.maxSingleTx(), rep.getTier().name()));
        }
    }

    /**
     * Records clean settlement points inside the settlement transaction.
     * Enforces lock ordering: allowance row -> wallet rows -> reputation row (locked last).
     */
    @Transactional
    public void recordCleanSettlement(OfflineReceiptDto receipt, Wallet senderWallet, Wallet receiverWallet) {
        UUID senderUserId = senderWallet.getUser().getId();
        UUID receiverUserId = receiverWallet.getUser().getId();

        // Lock reputation row for update (locked LAST after allowance and wallets)
        UserReputation reputation = userReputationRepository.findByUserIdForUpdate(senderUserId)
                .orElseGet(() -> getOrCreateReputation(senderUserId));

        // Anti-gaming: Wash trade detection (sender and receiver belong to the same user)
        boolean isWashTrade = senderUserId.equals(receiverUserId);

        int delta = 0;
        String reason;

        if (isWashTrade) {
            reason = "Clean settlement (wash trading between own wallets detected; 0 reputation points awarded)";
            log.warn("Wash trade detected for user {} on receipt {}. 0 reputation points awarded.", senderUserId, receipt.txId());
        } else {
            // Anti-gaming: Daily gain cap
            Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
            int gainToday = reputationEventRepository.sumScoreDeltaByUserIdAndEventTypeSince(
                    senderUserId, ReputationEventType.CLEAN_SETTLEMENT, startOfDay);

            int allowedPoints = Math.max(0, properties.getDailyGainCap() - gainToday);
            delta = Math.min(properties.getCleanReceiptPoints(), allowedPoints);
            reason = delta > 0
                    ? String.format("Clean receipt settlement (+%d points)", delta)
                    : "Clean receipt settlement (daily reputation gain cap reached; 0 points awarded)";
        }

        reputation.setCleanSettledCount(reputation.getCleanSettledCount() + 1);
        reputation.setSettledAmountTotal(reputation.getSettledAmountTotal().add(receipt.amount()));
        reputation.setScore(reputation.getScore() + delta);

        ReputationEvent event = new ReputationEvent(
                reputation.getUser(),
                ReputationEventType.CLEAN_SETTLEMENT,
                delta,
                reputation.getScore(),
                reason,
                receipt.txId().toString()
        );
        reputationEventRepository.save(event);

        evaluateTierTransition(reputation);
        userReputationRepository.save(reputation);
    }

    /**
     * Checks if an allowance completed cleanly with all receipts settled and awards bonus.
     */
    @Transactional
    public void checkAndAwardAllowanceCompletedBonus(OfflineAllowance allowance) {
        if (allowance == null || allowance.getStatus() == com.payflow.core.domain.offline.AllowanceStatus.REVOKED) {
            return;
        }

        UUID allowanceId = allowance.getId();
        UUID userId = allowance.getUser().getId();

        // Verify there were no rejections on this allowance
        boolean hasRejections = rejectedReceiptRepository.existsByAllowanceId(allowanceId);
        if (hasRejections) {
            return;
        }

        // Must have settled at least one transaction
        if (allowance.getSpentAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        UserReputation reputation = userReputationRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> getOrCreateReputation(userId));

        int bonus = properties.getAllowanceCompletionBonus();
        reputation.setScore(reputation.getScore() + bonus);

        ReputationEvent event = new ReputationEvent(
                reputation.getUser(),
                ReputationEventType.ALLOWANCE_COMPLETED_BONUS,
                bonus,
                reputation.getScore(),
                "Bonus for successfully completing offline allowance with all receipts cleanly settled",
                allowanceId.toString()
        );
        reputationEventRepository.save(event);

        evaluateTierTransition(reputation);
        userReputationRepository.save(reputation);
        log.info("Awarded allowance completion bonus of {} to user {}", bonus, userId);
    }

    /**
     * Penalizes sender for sender-attributable rejections (invalid signature, limits exceeded, replay).
     */
    @Transactional
    public void recordRejectionPenalty(UUID userId, String txId, String reason, RejectionCategory category) {
        if (category != RejectionCategory.SENDER_ATTRIBUTABLE) {
            log.debug("Rejection for user {} is not sender-attributable ({}); skipping reputation penalty", userId, category);
            return;
        }

        UserReputation reputation = userReputationRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> getOrCreateReputation(userId));

        reputation.setRejectedCount(reputation.getRejectedCount() + 1);
        int penalty = properties.getRejectionPenalty();
        int newScore = Math.max(0, reputation.getScore() - penalty);
        int delta = newScore - reputation.getScore();
        reputation.setScore(newScore);

        ReputationEvent event = new ReputationEvent(
                reputation.getUser(),
                ReputationEventType.SENDER_REJECTION_PENALTY,
                delta,
                reputation.getScore(),
                "Sender-attributable rejection penalty: " + reason,
                txId
        );
        reputationEventRepository.save(event);

        evaluateTierTransition(reputation);
        userReputationRepository.save(reputation);
        log.warn("Applied sender rejection penalty ({}) to user {}: new score {}", delta, userId, newScore);
    }

    /**
     * Triggered on fraud proof detection: applies major penalty and sets tier to RESTRICTED immediately.
     */
    @Transactional
    public void recordFraudDetected(UUID userId, UUID proofId) {
        UserReputation reputation = userReputationRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> getOrCreateReputation(userId));

        reputation.setFraudProofsDetected(reputation.getFraudProofsDetected() + 1);
        reputation.setRestricted(true);

        int penalty = properties.getFraudPenalty();
        int newScore = Math.max(0, reputation.getScore() - penalty);
        int delta = newScore - reputation.getScore();
        reputation.setScore(newScore);

        ReputationTier previousTier = reputation.getTier();
        reputation.setTier(ReputationTier.RESTRICTED);

        ReputationEvent event = new ReputationEvent(
                reputation.getUser(),
                ReputationEventType.FRAUD_PROOF_RESTRICTION,
                delta,
                reputation.getScore(),
                "Offline equivocation double-spend fraud proof detected. Account restricted from offline operations.",
                proofId.toString()
        );
        reputationEventRepository.save(event);

        userReputationRepository.save(reputation);

        if (previousTier != ReputationTier.RESTRICTED) {
            emitTierChangedEvent(userId, previousTier, ReputationTier.RESTRICTED, reputation.getScore());
        }

        log.error("Fraud proof detected for user {}. Penalized {} points, tier set to RESTRICTED.", userId, delta);
    }

    /**
     * Triggered when fraud proof is administratively confirmed.
     */
    @Transactional
    public void recordFraudConfirmed(UUID userId, UUID proofId) {
        UserReputation reputation = userReputationRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> getOrCreateReputation(userId));

        reputation.setFraudProofsConfirmed(reputation.getFraudProofsConfirmed() + 1);
        reputation.setRestricted(true);
        reputation.setTier(ReputationTier.RESTRICTED);

        ReputationEvent event = new ReputationEvent(
                reputation.getUser(),
                ReputationEventType.FRAUD_PROOF_CONFIRMED,
                0,
                reputation.getScore(),
                "Offline fraud proof confirmed by administrative review. Restriction maintained.",
                proofId.toString()
        );
        reputationEventRepository.save(event);
        userReputationRepository.save(reputation);
        log.info("Confirmed fraud proof for user {}. Account remains RESTRICTED.", userId);
    }

    /**
     * Triggered when fraud proof is administratively dismissed: restores penalty and unblocks user if no other active proofs.
     */
    @Transactional
    public void restoreDismissedProof(UUID userId, UUID proofId) {
        UserReputation reputation = userReputationRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> getOrCreateReputation(userId));

        int restoration = properties.getFraudPenalty();
        reputation.setScore(reputation.getScore() + restoration);

        // Check if there are other unreviewed fraud proofs
        long unconfirmedOrConfirmedProofs = fraudProofRepository.findByAllowanceUserId(userId).stream()
                .filter(p -> p.getStatus() != FraudProofStatus.DISMISSED)
                .count();

        if (unconfirmedOrConfirmedProofs == 0) {
            reputation.setRestricted(false);
        }

        ReputationEvent event = new ReputationEvent(
                reputation.getUser(),
                ReputationEventType.FRAUD_PROOF_DISMISSED_RESTORATION,
                restoration,
                reputation.getScore(),
                "Fraud proof dismissed upon administrative review; score restored",
                proofId.toString()
        );
        reputationEventRepository.save(event);

        evaluateTierTransition(reputation);
        userReputationRepository.save(reputation);
        log.info("Restored {} reputation points for user {} following fraud proof dismissal. New score: {}",
                restoration, userId, reputation.getScore());
    }

    /**
     * Administrative override of user reputation with audit logging.
     */
    @Transactional
    public ReputationResponse adminOverride(UUID userId, ReputationOverrideRequest request, String adminUsername) {
        UserReputation reputation = userReputationRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> getOrCreateReputation(userId));

        ReputationTier prevTier = reputation.getTier();

        if (request.score() != null) {
            reputation.setScore(request.score());
        }
        if (request.restricted() != null) {
            reputation.setRestricted(request.restricted());
        }
        if (request.tier() != null) {
            reputation.setTier(request.tier());
        } else if (!reputation.isRestricted()) {
            evaluateTierTransition(reputation);
        }

        ReputationEvent event = new ReputationEvent(
                reputation.getUser(),
                ReputationEventType.ADMIN_OVERRIDE,
                0,
                reputation.getScore(),
                String.format("Admin override by %s: %s", adminUsername, request.reason()),
                userId.toString()
        );
        reputationEventRepository.save(event);

        userReputationRepository.save(reputation);

        if (prevTier != reputation.getTier()) {
            emitTierChangedEvent(userId, prevTier, reputation.getTier(), reputation.getScore());
        }

        log.info("Admin {} overridden reputation for user {}: tier={}, score={}, restricted={}",
                adminUsername, userId, reputation.getTier(), reputation.getScore(), reputation.isRestricted());

        return getReputationResponse(userId);
    }

    /**
     * Evaluates tier transitions with anti-gaming criteria and hysteresis bands.
     */
    private void evaluateTierTransition(UserReputation reputation) {
        if (reputation.isRestricted()) {
            if (reputation.getTier() != ReputationTier.RESTRICTED) {
                ReputationTier prev = reputation.getTier();
                reputation.setTier(ReputationTier.RESTRICTED);
                emitTierChangedEvent(reputation.getUserId(), prev, ReputationTier.RESTRICTED, reputation.getScore());
            }
            return;
        }

        int score = reputation.getScore();
        ReputationTier currentTier = reputation.getTier();
        int hysteresis = properties.getHysteresis();

        // Anti-gaming requirements for promotion above NEW:
        long accountAgeDays = Duration.between(reputation.getUser().getCreatedAt(), Instant.now()).toDays();
        long distinctCounterparties = offlineTransactionRepository.countDistinctReceiversBySenderUserId(reputation.getUserId());

        boolean eligibleForPromotion = accountAgeDays >= properties.getMinAccountAgeDays()
                && distinctCounterparties >= properties.getMinDistinctCounterparties();

        ReputationTier newTier = currentTier;

        if (!eligibleForPromotion) {
            // Cannot be above NEW regardless of score
            newTier = ReputationTier.NEW;
        } else {
            // Determine tier based on thresholds and hysteresis
            int goldMin = properties.getTiers().get(ReputationTier.GOLD).minScore();
            int silverMin = properties.getTiers().get(ReputationTier.SILVER).minScore();
            int bronzeMin = properties.getTiers().get(ReputationTier.BRONZE).minScore();

            if (currentTier == ReputationTier.GOLD) {
                if (score < (goldMin - hysteresis)) {
                    newTier = score >= silverMin ? ReputationTier.SILVER
                            : (score >= bronzeMin ? ReputationTier.BRONZE : ReputationTier.NEW);
                }
            } else if (currentTier == ReputationTier.SILVER) {
                if (score >= goldMin) {
                    newTier = ReputationTier.GOLD;
                } else if (score < (silverMin - hysteresis)) {
                    newTier = score >= bronzeMin ? ReputationTier.BRONZE : ReputationTier.NEW;
                }
            } else if (currentTier == ReputationTier.BRONZE) {
                if (score >= goldMin) {
                    newTier = ReputationTier.GOLD;
                } else if (score >= silverMin) {
                    newTier = ReputationTier.SILVER;
                } else if (score < (bronzeMin - hysteresis)) {
                    newTier = ReputationTier.NEW;
                }
            } else {
                // Currently NEW or RESTRICTED transitioning out
                if (score >= goldMin) {
                    newTier = ReputationTier.GOLD;
                } else if (score >= silverMin) {
                    newTier = ReputationTier.SILVER;
                } else if (score >= bronzeMin) {
                    newTier = ReputationTier.BRONZE;
                } else {
                    newTier = ReputationTier.NEW;
                }
            }
        }

        if (newTier != currentTier) {
            reputation.setTier(newTier);
            emitTierChangedEvent(reputation.getUserId(), currentTier, newTier, score);
        }
    }

    private void emitTierChangedEvent(UUID userId, ReputationTier previousTier, ReputationTier newTier, int score) {
        try {
            ReputationTierChangedEvent event = new ReputationTierChangedEvent(
                    UUID.randomUUID(),
                    userId,
                    previousTier,
                    newTier,
                    score,
                    Instant.now()
            );
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent(
                    "USER_REPUTATION",
                    userId.toString(),
                    "REPUTATION_TIER_CHANGED",
                    payload
            );
            outboxEventRepository.save(outboxEvent);

            if (metricsService != null) {
                metricsService.recordReputationTierChange(previousTier, newTier);
            }
            log.info("Emitted ReputationTierChangedEvent for user {}: {} -> {}", userId, previousTier, newTier);
        } catch (Exception ex) {
            log.error("Failed to enqueue outbox event for reputation tier change", ex);
            throw new RuntimeException("Outbox persistence failure for reputation tier change", ex);
        }
    }

    @Transactional
    public ReputationResponse getReputationResponse(UUID userId) {
        UserReputation rep = userReputationRepository.findByUserId(userId)
                .orElseGet(() -> getOrCreateReputation(userId));

        ReputationProperties.TierLimit limit = properties.getEffectiveLimit(rep.getTier());

        // Calculate progress to next tier
        NextTierProgress nextProgress = calculateNextTierProgress(rep);

        // Fetch recent 20 audit events
        List<ReputationEventResponse> events = reputationEventRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20))
                .stream()
                .map(ReputationEventResponse::from)
                .toList();

        return new ReputationResponse(
                rep.getUserId(),
                rep.getScore(),
                rep.getTier(),
                limit.maxAllowance(),
                limit.maxSingleTx(),
                rep.getCleanSettledCount(),
                rep.getSettledAmountTotal(),
                rep.getRejectedCount(),
                rep.getFraudProofsDetected(),
                rep.getFraudProofsConfirmed(),
                rep.isRestricted(),
                nextProgress,
                events,
                rep.getUpdatedAt()
        );
    }

    private NextTierProgress calculateNextTierProgress(UserReputation rep) {
        if (rep.isRestricted() || rep.getTier() == ReputationTier.GOLD) {
            return new NextTierProgress(null, 0, 0, 0);
        }

        long accountAgeDays = Duration.between(rep.getUser().getCreatedAt(), Instant.now()).toDays();
        long distinctCounterparties = offlineTransactionRepository.countDistinctReceiversBySenderUserId(rep.getUserId());

        if (rep.getTier() == ReputationTier.NEW) {
            int bronzeMin = properties.getTiers().get(ReputationTier.BRONZE).minScore();
            int scoreNeeded = Math.max(0, bronzeMin - rep.getScore());
            int daysRemaining = Math.max(0, properties.getMinAccountAgeDays() - (int) accountAgeDays);
            long counterpartiesRemaining = Math.max(0, properties.getMinDistinctCounterparties() - distinctCounterparties);
            return new NextTierProgress(ReputationTier.BRONZE, scoreNeeded, daysRemaining, counterpartiesRemaining);
        } else if (rep.getTier() == ReputationTier.BRONZE) {
            int silverMin = properties.getTiers().get(ReputationTier.SILVER).minScore();
            int scoreNeeded = Math.max(0, silverMin - rep.getScore());
            return new NextTierProgress(ReputationTier.SILVER, scoreNeeded, 0, 0);
        } else if (rep.getTier() == ReputationTier.SILVER) {
            int goldMin = properties.getTiers().get(ReputationTier.GOLD).minScore();
            int scoreNeeded = Math.max(0, goldMin - rep.getScore());
            return new NextTierProgress(ReputationTier.GOLD, scoreNeeded, 0, 0);
        }

        return new NextTierProgress(null, 0, 0, 0);
    }
}
