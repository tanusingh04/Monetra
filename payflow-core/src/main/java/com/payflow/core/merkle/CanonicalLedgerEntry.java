package com.payflow.core.merkle;

import com.payflow.core.domain.ledger.LedgerEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class CanonicalLedgerEntry {

    public static final String GENESIS_PREVIOUS_ROOT = "0000000000000000000000000000000000000000000000000000000000000000";

    private CanonicalLedgerEntry() {}

    public static String toCanonicalString(LedgerEntry entry) {
        UUID paymentId = entry.getPayment() != null ? entry.getPayment().getId() : null;
        UUID walletId = entry.getWallet() != null ? entry.getWallet().getId() : null;
        String entryType = entry.getEntryType() != null ? entry.getEntryType().name() : "";
        return toCanonicalString(
                entry.getId(),
                paymentId,
                walletId,
                entryType,
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }

    public static String toCanonicalString(UUID entryId, UUID paymentId, UUID walletId,
                                           String entryType, BigDecimal amount,
                                           BigDecimal balanceAfter, Instant createdAt) {
        String amtStr = amount != null ? amount.setScale(4, RoundingMode.HALF_UP).toPlainString() : "0.0000";
        String balStr = balanceAfter != null ? balanceAfter.setScale(4, RoundingMode.HALF_UP).toPlainString() : "0.0000";
        long epochMicros = createdAt != null
                ? (createdAt.getEpochSecond() * 1_000_000L + (createdAt.getNano() / 1_000L))
                : 0L;

        return entryId + "|" + paymentId + "|" + walletId + "|" + entryType + "|" + amtStr + "|" + balStr + "|" + epochMicros;
    }

    public static String computeLeafHash(LedgerEntry entry) {
        return computeLeafHash(toCanonicalString(entry));
    }

    public static String computeLeafHash(String canonicalString) {
        byte[] canonicalBytes = canonicalString.getBytes(StandardCharsets.UTF_8);
        return computeLeafHash(canonicalBytes);
    }

    public static String computeLeafHash(byte[] canonicalBytes) {
        byte[] input = new byte[1 + canonicalBytes.length];
        input[0] = 0x00; // RFC 6962 leaf prefix
        System.arraycopy(canonicalBytes, 0, input, 1, canonicalBytes.length);
        return sha256Hex(input);
    }

    public static String computeNodeHash(String leftHex, String rightHex) {
        byte[] left = HexFormat.of().parseHex(leftHex);
        byte[] right = HexFormat.of().parseHex(rightHex);
        byte[] input = new byte[1 + 32 + 32];
        input[0] = 0x01; // RFC 6962 internal node prefix
        System.arraycopy(left, 0, input, 1, 32);
        System.arraycopy(right, 0, input, 33, 32);
        return sha256Hex(input);
    }

    public static String computeChainHash(String previousRootHash, String rootHash, Long batchNumber, int leafCount) {
        String data = previousRootHash + rootHash + batchNumber + leafCount;
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }
}
