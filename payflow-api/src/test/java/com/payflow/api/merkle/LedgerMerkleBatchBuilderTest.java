package com.payflow.api.merkle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.metrics.PayFlowMetricsService;
import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.merkle.LedgerMerkleBatch;
import com.payflow.core.domain.outbox.OutboxEvent;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.merkle.CanonicalLedgerEntry;
import com.payflow.core.merkle.MerkleVerifier;
import com.payflow.core.repository.LedgerEntryRepository;
import com.payflow.core.repository.LedgerMerkleBatchRepository;
import com.payflow.core.repository.LedgerMerkleLeafRepository;
import com.payflow.core.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LedgerMerkleBatchBuilderTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private LedgerMerkleBatchRepository batchRepository;

    @Mock
    private LedgerMerkleLeafRepository leafRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RootAnchorPublisher rootAnchorPublisher;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PayFlowMetricsService metricsService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MerkleProperties properties;
    private LedgerMerkleBatchBuilder batchBuilder;

    @BeforeEach
    void setUp() {
        properties = new MerkleProperties();
        properties.getBatchBuilder().setEnabled(true);
        properties.getBatchBuilder().setBatchSize(10);
        properties.getBatchBuilder().setLagSeconds(60);

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                .thenReturn(true);

        batchBuilder = new LedgerMerkleBatchBuilder(
                ledgerEntryRepository, batchRepository, leafRepository, outboxEventRepository,
                rootAnchorPublisher, properties, jdbcTemplate, objectMapper, metricsService
        );
    }

    @Test
    @DisplayName("Builds genesis batch when unanchored entries exist and no previous batch")
    void testBuildGenesisBatch() {
        User user = new User("alice", "alice@test.com", "hash", UserRole.ROLE_USER);
        user.setId(UUID.randomUUID());
        Wallet wallet = new Wallet(user, "INR", BigDecimal.valueOf(1000));
        wallet.setId(UUID.randomUUID());
        Payment payment = new Payment(UUID.randomUUID().toString(), wallet, wallet, BigDecimal.valueOf(50), "INR");
        payment.setId(UUID.randomUUID());

        LedgerEntry entry1 = new LedgerEntry(payment, wallet, EntryType.DEBIT, BigDecimal.valueOf(50), BigDecimal.valueOf(950));
        LedgerEntry entry2 = new LedgerEntry(payment, wallet, EntryType.CREDIT, BigDecimal.valueOf(50), BigDecimal.valueOf(1000));

        when(batchRepository.findTopByOrderByBatchNumberDesc()).thenReturn(Optional.empty());
        when(ledgerEntryRepository.findUnanchoredEntries(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(entry1, entry2));

        Optional<LedgerMerkleBatch> resultOpt = batchBuilder.buildNextBatch();

        assertThat(resultOpt).isPresent();
        LedgerMerkleBatch batch = resultOpt.get();
        assertThat(batch.getBatchNumber()).isEqualTo(1L);
        assertThat(batch.getLeafCount()).isEqualTo(2);
        assertThat(batch.getPreviousRootHash()).isEqualTo(CanonicalLedgerEntry.GENESIS_PREVIOUS_ROOT);
        assertThat(batch.getRootHash()).hasSize(64);
        assertThat(batch.getChainHash()).hasSize(64);

        verify(batchRepository).save(any(LedgerMerkleBatch.class));
        verify(leafRepository).saveAll(argThat(leaves -> ((List<?>) leaves).size() == 2));
        verify(rootAnchorPublisher).publishRootAnchor(eq(1L), eq(2), eq(batch.getRootHash()), eq(batch.getChainHash()));
        verify(outboxEventRepository).save(argThat(outbox -> "LEDGER_ROOT_PUBLISHED".equals(outbox.getEventType())));
        verify(metricsService).incrementMerkleBatchesBuilt();
        verify(metricsService).incrementMerkleLeaves(2);
    }

    @Test
    @DisplayName("Links subsequent batch to previous batch root hash")
    void testLinkSubsequentBatch() {
        LedgerMerkleBatch prevBatch = new LedgerMerkleBatch(
                1L, 5, "1111111111111111111111111111111111111111111111111111111111111111",
                CanonicalLedgerEntry.GENESIS_PREVIOUS_ROOT, "chain-1",
                Instant.now(), Instant.now()
        );

        User user = new User("alice", "alice@test.com", "hash", UserRole.ROLE_USER);
        user.setId(UUID.randomUUID());
        Wallet wallet = new Wallet(user, "INR", BigDecimal.valueOf(1000));
        wallet.setId(UUID.randomUUID());
        Payment payment = new Payment(UUID.randomUUID().toString(), wallet, wallet, BigDecimal.valueOf(10), "INR");
        payment.setId(UUID.randomUUID());
        LedgerEntry entry = new LedgerEntry(payment, wallet, EntryType.DEBIT, BigDecimal.valueOf(10), BigDecimal.valueOf(990));

        when(batchRepository.findTopByOrderByBatchNumberDesc()).thenReturn(Optional.of(prevBatch));
        when(ledgerEntryRepository.findUnanchoredEntries(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(entry));

        Optional<LedgerMerkleBatch> resultOpt = batchBuilder.buildNextBatch();

        assertThat(resultOpt).isPresent();
        LedgerMerkleBatch batch = resultOpt.get();
        assertThat(batch.getBatchNumber()).isEqualTo(2L);
        assertThat(batch.getPreviousRootHash()).isEqualTo(prevBatch.getRootHash());
    }

    @Test
    @DisplayName("Skips execution when no unanchored entries are eligible")
    void testSkipsWhenNoEligibleEntries() {
        when(ledgerEntryRepository.findUnanchoredEntries(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        Optional<LedgerMerkleBatch> resultOpt = batchBuilder.buildNextBatch();

        assertThat(resultOpt).isEmpty();
        verify(batchRepository, never()).save(any());
        verify(leafRepository, never()).saveAll(any());
        verifyNoInteractions(rootAnchorPublisher);
    }

    @Test
    @DisplayName("Skips execution when advisory lock is not acquired")
    void testSkipsWhenAdvisoryLockNotAcquired() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                .thenReturn(false);

        Optional<LedgerMerkleBatch> resultOpt = batchBuilder.buildNextBatch();

        assertThat(resultOpt).isEmpty();
        verify(ledgerEntryRepository, never()).findUnanchoredEntries(any(), any());
    }
}
