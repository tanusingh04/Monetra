package com.payflow.api.merkle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.merkle.MerkleProofResponse;
import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.merkle.LedgerMerkleBatch;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class LedgerMerkleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LedgerMerkleBatchBuilder batchBuilder;

    @Autowired
    private LedgerMerkleBatchRepository batchRepository;

    @Autowired
    private LedgerMerkleLeafRepository leafRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserReputationRepository userReputationRepository;

    @Autowired
    private ReputationEventRepository reputationEventRepository;

    @Autowired
    private OfflineProtectedSettlementRepository protectedSettlementRepository;

    @Autowired
    private OfflineRejectedReceiptRepository rejectedReceiptRepository;

    @Autowired
    private FraudProofRepository fraudProofRepository;

    @Autowired
    private OfflineTransactionRepository offlineTransactionRepository;

    @Autowired
    private OfflineAllowanceRepository allowanceRepository;

    @Autowired
    private DeviceEnrollmentRepository deviceEnrollmentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private MerkleProperties merkleProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private User alice;
    private User bob;
    private Wallet aliceWallet;
    private Wallet bobWallet;
    private LedgerEntry aliceEntry;

    @BeforeEach
    void setUp() {
        try {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_fraud_proofs");
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_reputation_events");
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_merkle_batches");
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_merkle_leaves");
        } catch (Exception ignored) {}

        leafRepository.deleteAll();
        batchRepository.deleteAll();
        protectedSettlementRepository.deleteAll();
        rejectedReceiptRepository.deleteAll();
        fraudProofRepository.deleteAll();
        offlineTransactionRepository.deleteAll();
        allowanceRepository.deleteAll();
        deviceEnrollmentRepository.deleteAll();
        outboxEventRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        paymentRepository.deleteAll();
        walletRepository.deleteAll();
        reputationEventRepository.deleteAll();
        userReputationRepository.deleteAll();
        notificationRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(new User("alice", "alice@merkle.test", "hash", UserRole.ROLE_USER));
        bob = userRepository.save(new User("bob", "bob@merkle.test", "hash", UserRole.ROLE_USER));

        aliceWallet = walletRepository.save(new Wallet(alice, "INR", new BigDecimal("1000.0000")));
        bobWallet = walletRepository.save(new Wallet(bob, "INR", new BigDecimal("500.0000")));

        Payment payment = new Payment(UUID.randomUUID().toString(), aliceWallet, bobWallet, new BigDecimal("100.0000"), "INR");
        payment = paymentRepository.save(payment);

        aliceEntry = new LedgerEntry(payment, aliceWallet, EntryType.DEBIT, new BigDecimal("100.0000"), new BigDecimal("900.0000"));
        aliceEntry = ledgerEntryRepository.save(aliceEntry);

        LedgerEntry bobEntry = new LedgerEntry(payment, bobWallet, EntryType.CREDIT, new BigDecimal("100.0000"), new BigDecimal("600.0000"));
        ledgerEntryRepository.save(bobEntry);

        // Configure lag to 0 for instantaneous test anchoring
        merkleProperties.getBatchBuilder().setLagSeconds(0);
    }

    @Test
    @DisplayName("Batch builder anchors eligible ledger entries and seals Merkle batch")
    void testBuildBatchAndSealRoot() {
        Optional<LedgerMerkleBatch> batchOpt = batchBuilder.buildNextBatch();

        assertThat(batchOpt).isPresent();
        LedgerMerkleBatch batch = batchOpt.get();
        assertThat(batch.getBatchNumber()).isEqualTo(1L);
        assertThat(batch.getLeafCount()).isEqualTo(2);
        assertThat(batch.getRootHash()).hasSize(64);
        assertThat(batch.getChainHash()).hasSize(64);

        assertThat(leafRepository.findAll()).hasSize(2);
    }

    @Test
    @WithMockUser(username = "alice", roles = {"USER"})
    @DisplayName("Wallet owner can retrieve inclusion proof for confirmed anchored entry")
    void testOwnerCanRetrieveProofWhenConfirmed() throws Exception {
        batchBuilder.buildNextBatch();

        mockMvc.perform(get("/api/ledger/entries/{entryId}/proof", aliceEntry.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.entryId").value(aliceEntry.getId().toString()))
                .andExpect(jsonPath("$.leafIndex").value(0))
                .andExpect(jsonPath("$.leafCount").value(2))
                .andExpect(jsonPath("$.batchNumber").value(1))
                .andExpect(jsonPath("$.rootHash").isNotEmpty())
                .andExpect(jsonPath("$.auditPath").isArray())
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    @WithMockUser(username = "bob", roles = {"USER"})
    @DisplayName("Non-owner user is forbidden (403) from retrieving another user's proof")
    void testNonOwnerForbiddenFromRetrievingProof() throws Exception {
        batchBuilder.buildNextBatch();

        mockMvc.perform(get("/api/ledger/entries/{entryId}/proof", aliceEntry.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Admin can retrieve proof for any user's ledger entry")
    void testAdminCanRetrieveProof() throws Exception {
        batchBuilder.buildNextBatch();

        mockMvc.perform(get("/api/ledger/entries/{entryId}/proof", aliceEntry.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    @WithMockUser(username = "alice", roles = {"USER"})
    @DisplayName("Unanchored ledger entry returns HTTP 202 Accepted with PENDING_ANCHORING status")
    void testUnanchoredEntryReturns202Accepted() throws Exception {
        // Do NOT run batchBuilder.buildNextBatch() -> entry remains unanchored
        mockMvc.perform(get("/api/ledger/entries/{entryId}/proof", aliceEntry.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_ANCHORING"))
                .andExpect(jsonPath("$.verified").value(false));
    }

    @Test
    @DisplayName("Public can query committed roots and latest root without authentication")
    void testPublicRootsEndpoints() throws Exception {
        batchBuilder.buildNextBatch();

        // 1. Paginated roots
        mockMvc.perform(get("/api/public/ledger/roots")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].batchNumber").value(1))
                .andExpect(jsonPath("$.content[0].leafCount").value(2));

        // 2. Latest root
        mockMvc.perform(get("/api/public/ledger/roots/latest")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNumber").value(1))
                .andExpect(jsonPath("$.leafCount").value(2));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Admin can verify Merkle batch integrity and chain linkage")
    void testAdminBatchVerification() throws Exception {
        batchBuilder.buildNextBatch();

        mockMvc.perform(post("/api/admin/ledger/batches/{batchNumber}/verify", 1L)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNumber").value(1))
                .andExpect(jsonPath("$.rootVerified").value(true))
                .andExpect(jsonPath("$.chainVerified").value(true))
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    @WithMockUser(username = "alice", roles = {"USER"})
    @DisplayName("Non-admin user cannot trigger admin batch verification (403 Forbidden)")
    void testNonAdminCannotVerifyBatch() throws Exception {
        batchBuilder.buildNextBatch();

        mockMvc.perform(post("/api/admin/ledger/batches/{batchNumber}/verify", 1L)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
