package com.payflow.api.merkle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.metrics.PayFlowMetricsService;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.merkle.LedgerMerkleBatch;
import com.payflow.core.domain.merkle.LedgerMerkleLeaf;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.event.LedgerRootPublishedEvent;
import com.payflow.core.merkle.CanonicalLedgerEntry;
import com.payflow.core.merkle.MerkleTree;
import com.payflow.core.repository.LedgerEntryRepository;
import com.payflow.core.repository.LedgerMerkleBatchRepository;
import com.payflow.core.repository.LedgerMerkleLeafRepository;
import com.payflow.core.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class LedgerMerkleBatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(LedgerMerkleBatchBuilder.class);
    private static final long MERKLE_ADVISORY_LOCK_KEY = 7429103L;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerMerkleBatchRepository batchRepository;
    private final LedgerMerkleLeafRepository leafRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RootAnchorPublisher rootAnchorPublisher;
    private final MerkleProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PayFlowMetricsService metricsService;

    @Autowired
    public LedgerMerkleBatchBuilder(LedgerEntryRepository ledgerEntryRepository,
                                   LedgerMerkleBatchRepository batchRepository,
                                   LedgerMerkleLeafRepository leafRepository,
                                   OutboxEventRepository outboxEventRepository,
                                   RootAnchorPublisher rootAnchorPublisher,
                                   MerkleProperties properties,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   @Autowired(required = false) PayFlowMetricsService metricsService) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.batchRepository = batchRepository;
        this.leafRepository = leafRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.rootAnchorPublisher = rootAnchorPublisher;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @Scheduled(fixedDelayString = "${app.merkle.batch-builder.interval-ms:60000}",
               initialDelayString = "${app.merkle.batch-builder.initial-delay-ms:10000}")
    public void scheduleBatchBuild() {
        if (!properties.getBatchBuilder().isEnabled()) {
            log.trace("Merkle batch builder is disabled; skipping execution.");
            return;
        }
        buildNextBatch();
    }

    @Transactional
    public Optional<LedgerMerkleBatch> buildNextBatch() {
        if (!tryAcquireAdvisoryLock()) {
            log.debug("Merkle batch builder advisory lock not acquired; another node is building a batch.");
            return Optional.empty();
        }

        Instant cutoff = Instant.now().minusSeconds(properties.getBatchBuilder().getLagSeconds());
        PageRequest pageRequest = PageRequest.of(0, properties.getBatchBuilder().getBatchSize());

        List<LedgerEntry> entries = ledgerEntryRepository.findUnanchoredEntries(cutoff, pageRequest);

        if (metricsService != null) {
            metricsService.setUnanchoredEntries(ledgerEntryRepository.countUnanchoredEntries());
        }

        if (entries.isEmpty()) {
            log.debug("No unanchored ledger entries eligible for batch anchoring (older than lag window {}s).",
                    properties.getBatchBuilder().getLagSeconds());
            return Optional.empty();
        }

        Optional<LedgerMerkleBatch> latestBatchOpt = batchRepository.findTopByOrderByBatchNumberDesc();
        long batchNumber = latestBatchOpt.map(b -> b.getBatchNumber() + 1L).orElse(1L);
        String previousRootHash = latestBatchOpt.map(LedgerMerkleBatch::getRootHash)
                .orElse(CanonicalLedgerEntry.GENESIS_PREVIOUS_ROOT);

        int leafCount = entries.size();
        List<String> leafHashes = new ArrayList<>(leafCount);
        List<LedgerMerkleLeaf> leaves = new ArrayList<>(leafCount);

        for (int i = 0; i < leafCount; i++) {
            LedgerEntry entry = entries.get(i);
            String leafHash = CanonicalLedgerEntry.computeLeafHash(entry);
            leafHashes.add(leafHash);
            leaves.add(new LedgerMerkleLeaf(entry.getId(), batchNumber, i, leafHash));
        }

        MerkleTree tree = new MerkleTree(leafHashes);
        String rootHash = tree.getRootHash();
        String chainHash = CanonicalLedgerEntry.computeChainHash(previousRootHash, rootHash, batchNumber, leafCount);

        Instant firstEntryCreatedAt = entries.get(0).getCreatedAt();
        Instant lastEntryCreatedAt = entries.get(leafCount - 1).getCreatedAt();

        LedgerMerkleBatch batch = new LedgerMerkleBatch(
                batchNumber, leafCount, rootHash, previousRootHash, chainHash,
                firstEntryCreatedAt, lastEntryCreatedAt
        );

        batchRepository.save(batch);
        leafRepository.saveAll(leaves);

        if (metricsService != null) {
            metricsService.incrementMerkleBatchesBuilt();
            metricsService.incrementMerkleLeaves(leafCount);
            metricsService.setUnanchoredEntries(ledgerEntryRepository.countUnanchoredEntries());
        }

        rootAnchorPublisher.publishRootAnchor(batchNumber, leafCount, rootHash, chainHash);

        try {
            LedgerRootPublishedEvent event = new LedgerRootPublishedEvent(
                    batchNumber, leafCount, rootHash, chainHash, previousRootHash, Instant.now()
            );
            OutboxEvent outboxEvent = new OutboxEvent(
                    "LEDGER_ROOT",
                    String.valueOf(batchNumber),
                    "LEDGER_ROOT_PUBLISHED",
                    objectMapper.writeValueAsString(event)
            );
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize LedgerRootPublishedEvent to JSON", e);
            throw new IllegalStateException("Failed to serialize LedgerRootPublishedEvent", e);
        }

        log.info("Successfully built Merkle batch #{} with {} entries. Root={}, Chain={}",
                batchNumber, leafCount, rootHash, chainHash);

        return Optional.of(batch);
    }

    private boolean tryAcquireAdvisoryLock() {
        try {
            Boolean locked = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, MERKLE_ADVISORY_LOCK_KEY);
            return Boolean.TRUE.equals(locked);
        } catch (Exception ex) {
            log.debug("Advisory lock not supported or bypass in current DB engine: {}", ex.getMessage());
            return true;
        }
    }
}
