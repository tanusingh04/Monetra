package com.payflow.api.config;

import com.payflow.core.domain.reputation.ReputationTier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.reputation")
public class ReputationProperties {

    private int startingScore = 100;
    private int cleanReceiptPoints = 5;
    private int dailyGainCap = 50;
    private int allowanceCompletionBonus = 20;
    private int rejectionPenalty = 10;
    private int fraudPenalty = 100;
    private int hysteresis = 15;
    private int minAccountAgeDays = 7;
    private int minDistinctCounterparties = 3;

    private BigDecimal globalMaxAllowance = new BigDecimal("2000.0000");
    private BigDecimal globalMaxSingleTx = new BigDecimal("500.0000");

    private Map<ReputationTier, TierLimit> tiers = new EnumMap<>(ReputationTier.class);

    public record TierLimit(int minScore, BigDecimal maxAllowance, BigDecimal maxSingleTx) {
        public TierLimit(int minScore, BigDecimal maxAllowance, BigDecimal maxSingleTx) {
            this.minScore = minScore;
            this.maxAllowance = maxAllowance.setScale(4, RoundingMode.HALF_UP);
            this.maxSingleTx = maxSingleTx.setScale(4, RoundingMode.HALF_UP);
        }
    }

    public ReputationProperties() {
        tiers.put(ReputationTier.RESTRICTED, new TierLimit(0, BigDecimal.ZERO, BigDecimal.ZERO));
        tiers.put(ReputationTier.NEW, new TierLimit(0, new BigDecimal("500.0000"), new BigDecimal("200.0000")));
        tiers.put(ReputationTier.BRONZE, new TierLimit(200, new BigDecimal("1000.0000"), new BigDecimal("300.0000")));
        tiers.put(ReputationTier.SILVER, new TierLimit(500, new BigDecimal("1500.0000"), new BigDecimal("400.0000")));
        tiers.put(ReputationTier.GOLD, new TierLimit(1000, new BigDecimal("2000.0000"), new BigDecimal("500.0000")));
    }

    public TierLimit getEffectiveLimit(ReputationTier tier) {
        TierLimit limit = tiers.getOrDefault(tier, tiers.get(ReputationTier.NEW));
        if (tier == ReputationTier.RESTRICTED) {
            return new TierLimit(0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal allowance = limit.maxAllowance().min(globalMaxAllowance);
        BigDecimal single = limit.maxSingleTx().min(globalMaxSingleTx);
        return new TierLimit(limit.minScore(), allowance, single);
    }

    public int getStartingScore() {
        return startingScore;
    }

    public void setStartingScore(int startingScore) {
        this.startingScore = startingScore;
    }

    public int getCleanReceiptPoints() {
        return cleanReceiptPoints;
    }

    public void setCleanReceiptPoints(int cleanReceiptPoints) {
        this.cleanReceiptPoints = cleanReceiptPoints;
    }

    public int getDailyGainCap() {
        return dailyGainCap;
    }

    public void setDailyGainCap(int dailyGainCap) {
        this.dailyGainCap = dailyGainCap;
    }

    public int getAllowanceCompletionBonus() {
        return allowanceCompletionBonus;
    }

    public void setAllowanceCompletionBonus(int allowanceCompletionBonus) {
        this.allowanceCompletionBonus = allowanceCompletionBonus;
    }

    public int getRejectionPenalty() {
        return rejectionPenalty;
    }

    public void setRejectionPenalty(int rejectionPenalty) {
        this.rejectionPenalty = rejectionPenalty;
    }

    public int getFraudPenalty() {
        return fraudPenalty;
    }

    public void setFraudPenalty(int fraudPenalty) {
        this.fraudPenalty = fraudPenalty;
    }

    public int getHysteresis() {
        return hysteresis;
    }

    public void setHysteresis(int hysteresis) {
        this.hysteresis = hysteresis;
    }

    public int getMinAccountAgeDays() {
        return minAccountAgeDays;
    }

    public void setMinAccountAgeDays(int minAccountAgeDays) {
        this.minAccountAgeDays = minAccountAgeDays;
    }

    public int getMinDistinctCounterparties() {
        return minDistinctCounterparties;
    }

    public void setMinDistinctCounterparties(int minDistinctCounterparties) {
        this.minDistinctCounterparties = minDistinctCounterparties;
    }

    public BigDecimal getGlobalMaxAllowance() {
        return globalMaxAllowance;
    }

    public void setGlobalMaxAllowance(BigDecimal globalMaxAllowance) {
        this.globalMaxAllowance = globalMaxAllowance.setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal getGlobalMaxSingleTx() {
        return globalMaxSingleTx;
    }

    public void setGlobalMaxSingleTx(BigDecimal globalMaxSingleTx) {
        this.globalMaxSingleTx = globalMaxSingleTx.setScale(4, RoundingMode.HALF_UP);
    }

    public Map<ReputationTier, TierLimit> getTiers() {
        return tiers;
    }

    public void setTiers(Map<ReputationTier, TierLimit> tiers) {
        this.tiers = tiers;
    }
}
