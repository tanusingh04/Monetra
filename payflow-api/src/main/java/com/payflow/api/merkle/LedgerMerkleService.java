package com.payflow.api.merkle;

import com.payflow.api.dto.merkle.BatchVerificationReport;
import com.payflow.api.dto.merkle.LedgerMerkleBatchResponse;
import com.payflow.api.dto.merkle.MerkleProofResponse;
import com.payflow.api.metrics.PayFlowMetricsService;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.merkle.LedgerMerkleBatch;
import com.payflow.core.domain.merkle.LedgerMerkleLeaf;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.exception.UnauthorizedAccessException;
import com.payflow.core.merkle.CanonicalLedgerEntry;
import com.payflow.core.merkle.MerkleAuditPathStep;
import com.payflow.core.merkle.MerkleTree;
import com.payflow.core.merkle.MerkleVerifier;
import com.payflow.core.repository.LedgerEntryRepository;
import com.payflow.core.repository.LedgerMerkleBatchRepository;
import com.payflow.core.repository.LedgerMerkleLeafRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class LedgerMerkleService {

    private static final Logger log = LoggerFactory.getLogger(LedgerMerkleService.class);

    private final LedgerEntryRepository entryRepository;
    private final LedgerMerkleBatchRepository batchRepository;
    private final LedgerMerkleLeafRepository leafRepository;
    private final PayFlowMetricsService metricsService;

    @Autowired
    public LedgerMerkleService(LedgerEntryRepository entryRepository,
                               LedgerMerkleBatchRepository batchRepository,
                               LedgerMerkleLeafRepository leafRepository,
                               @Autowired(required = false) PayFlowMetricsService metricsService) {
        this.entryRepository = entryRepository;
        this.batchRepository = batchRepository;
        this.leafRepository = leafRepository;
        this.metricsService = metricsService;
    }

    public Optional<MerkleProofResponse> getMerkleProof(UUID entryId, String username, boolean isAdmin) {
        LedgerEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger entry not found with id: " + entryId));

        if (!isAdmin) {
            if (entry.getWallet() == null || entry.getWallet().getUser() == null ||
                    !username.equals(entry.getWallet().getUser().getUsername())) {
                throw new UnauthorizedAccessException("You do not have permission to view proofs for this ledger entry");
            }
        }

        Optional<LedgerMerkleLeaf> leafOpt = leafRepository.findByEntryId(entryId);
        if (leafOpt.isEmpty()) {
            return Optional.empty(); // Not anchored yet -> caller returns HTTP 202 Accepted
        }

        LedgerMerkleLeaf leaf = leafOpt.get();
        LedgerMerkleBatch batch = batchRepository.findById(leaf.getBatchNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Merkle batch not found: " + leaf.getBatchNumber()));

        List<LedgerMerkleLeaf> batchLeaves = leafRepository.findAllByBatchNumberOrderByLeafIndexAsc(batch.getBatchNumber());
        List<String> leafHashes = batchLeaves.stream().map(LedgerMerkleLeaf::getLeafHash).toList();

        MerkleTree tree = new MerkleTree(leafHashes);
        List<MerkleAuditPathStep> auditPath = tree.getAuditPath(leaf.getLeafIndex());
        String canonicalPayload = CanonicalLedgerEntry.toCanonicalString(entry);

        boolean verified = MerkleVerifier.verifyInclusion(
                leaf.getLeafHash(), leaf.getLeafIndex(), batch.getLeafCount(), auditPath, batch.getRootHash()
        );

        return Optional.of(new MerkleProofResponse(
                "CONFIRMED",
                entryId,
                leaf.getLeafHash(),
                leaf.getLeafIndex(),
                batch.getLeafCount(),
                batch.getBatchNumber(),
                batch.getRootHash(),
                batch.getChainHash(),
                batch.getPreviousRootHash(),
                auditPath,
                canonicalPayload,
                verified,
                verified ? "Proof cryptographically verified against batch root" : "Inclusion verification failed"
        ));
    }

    public Page<LedgerMerkleBatchResponse> getRoots(Pageable pageable) {
        return batchRepository.findAllByOrderByBatchNumberDesc(pageable)
                .map(LedgerMerkleBatchResponse::fromEntity);
    }

    public Optional<LedgerMerkleBatchResponse> getLatestRoot() {
        return batchRepository.findTopByOrderByBatchNumberDesc()
                .map(LedgerMerkleBatchResponse::fromEntity);
    }

    public BatchVerificationReport verifyBatch(Long batchNumber) {
        LedgerMerkleBatch batch = batchRepository.findById(batchNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Merkle batch not found: " + batchNumber));

        List<LedgerMerkleLeaf> leaves = leafRepository.findAllByBatchNumberOrderByLeafIndexAsc(batchNumber);
        if (leaves.isEmpty() || leaves.size() != batch.getLeafCount()) {
            if (metricsService != null) metricsService.incrementMerkleVerifyFailures();
            return new BatchVerificationReport(
                    batchNumber, batch.getLeafCount(), batch.getRootHash(), null, false,
                    batch.getChainHash(), null, false, false, Instant.now()
            );
        }

        List<String> hashes = leaves.stream().map(LedgerMerkleLeaf::getLeafHash).toList();
        MerkleTree tree = new MerkleTree(hashes);
        String recomputedRoot = tree.getRootHash();
        boolean rootVerified = recomputedRoot.equalsIgnoreCase(batch.getRootHash());

        String recomputedChain = CanonicalLedgerEntry.computeChainHash(
                batch.getPreviousRootHash(), batch.getRootHash(), batch.getBatchNumber(), batch.getLeafCount()
        );
        boolean chainVerified = recomputedChain.equalsIgnoreCase(batch.getChainHash());

        if (batchNumber > 1L) {
            Optional<LedgerMerkleBatch> prevBatchOpt = batchRepository.findById(batchNumber - 1L);
            if (prevBatchOpt.isEmpty() || !prevBatchOpt.get().getRootHash().equalsIgnoreCase(batch.getPreviousRootHash())) {
                chainVerified = false;
            }
        }

        boolean valid = rootVerified && chainVerified;
        if (!valid && metricsService != null) {
            metricsService.incrementMerkleVerifyFailures();
        }

        return new BatchVerificationReport(
                batchNumber,
                batch.getLeafCount(),
                batch.getRootHash(),
                recomputedRoot,
                rootVerified,
                batch.getChainHash(),
                recomputedChain,
                chainVerified,
                valid,
                Instant.now()
        );
    }
}
