package com.payflow.api.concurrency;

import com.payflow.api.dto.payment.TransferRequest;
import com.payflow.api.service.PaymentProcessingService;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.InsufficientBalanceException;
import com.payflow.core.repository.UserRepository;
import com.payflow.core.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class ConcurrentTransferIntegrationTest {

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private User senderUser;
    private User receiverUser;
    private Wallet senderWallet;
    private Wallet receiverWallet;

    @BeforeEach
    void setUp() {
        try {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_immutable_reputation_events");
            jdbcTemplate.execute("DELETE FROM notifications");
            jdbcTemplate.execute("DELETE FROM reputation_events");
            jdbcTemplate.execute("DELETE FROM user_reputation");
        } catch (Exception ignored) {}
        walletRepository.deleteAll();
        userRepository.deleteAll();

        senderUser = userRepository.save(new User("concurrent_sender", "sender@test.com", "hash", UserRole.ROLE_USER));
        receiverUser = userRepository.save(new User("concurrent_receiver", "receiver@test.com", "hash", UserRole.ROLE_USER));

        senderWallet = walletRepository.save(new Wallet(senderUser, "INR", BigDecimal.valueOf(1000)));
        receiverWallet = walletRepository.save(new Wallet(receiverUser, "INR", BigDecimal.valueOf(0)));
    }

    @Test
    @DisplayName("Concurrency Control: Two concurrent ₹800 withdrawals on a ₹1,000 wallet -> exactly 1 succeeds, balance never negative")
    void testConcurrentWithdrawalsPreventNegativeBalance() throws Exception {
        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Ensure both threads release at the exact same instant
                    TransferRequest request = new TransferRequest(
                            senderWallet.getId(),
                            receiverWallet.getId(),
                            BigDecimal.valueOf(800),
                            "INR"
                    );
                    paymentProcessingService.transfer(request, "idemp-key-" + threadIndex, senderUser.getId());
                    successCount.incrementAndGet();
                } catch (InsufficientBalanceException e) {
                    insufficientFundsCount.incrementAndGet();
                } catch (Throwable e) {
                    e.printStackTrace();
                    Throwable cause = e.getCause();
                    while (cause != null) {
                        if (cause instanceof InsufficientBalanceException) {
                            insufficientFundsCount.incrementAndGet();
                            return;
                        }
                        cause = cause.getCause();
                    }
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Fire both threads simultaneously

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // Verification of concurrency invariants
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(insufficientFundsCount.get()).isEqualTo(1);

        Wallet updatedSender = walletRepository.findById(senderWallet.getId()).orElseThrow();
        Wallet updatedReceiver = walletRepository.findById(receiverWallet.getId()).orElseThrow();

        assertThat(updatedSender.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(200));
        assertThat(updatedReceiver.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(800));
        assertThat(updatedSender.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}
