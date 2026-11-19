package com.payflow.core.domain.wallet;

import com.payflow.core.domain.user.User;
import com.payflow.core.exception.InsufficientBalanceException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

    @Column(name = "reserved_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedBalance = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletStatus status = WalletStatus.ACTIVE;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Wallet() {}

    public Wallet(User user, String currency, BigDecimal initialBalance) {
        this.user = user;
        this.currency = currency != null ? currency.toUpperCase() : "INR";
        this.balance = (initialBalance != null ? initialBalance : BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        this.status = WalletStatus.ACTIVE;
        this.version = 0L;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public synchronized BigDecimal getAvailableBalance() {
        return this.balance.subtract(this.reservedBalance).setScale(4, RoundingMode.HALF_UP);
    }

    public synchronized void debit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be strictly positive");
        }
        BigDecimal scaledAmount = amount.setScale(4, RoundingMode.HALF_UP);
        BigDecimal available = getAvailableBalance();
        if (available.compareTo(scaledAmount) < 0) {
            throw new InsufficientBalanceException(
                "Insufficient funds in wallet " + this.id + ". Available: " + available + " (Balance: " + this.balance + ", Reserved: " + this.reservedBalance + "), requested debit: " + scaledAmount
            );
        }
        this.balance = this.balance.subtract(scaledAmount);
    }

    public synchronized void reserveFunds(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Reserve amount must be strictly positive");
        }
        BigDecimal scaledAmount = amount.setScale(4, RoundingMode.HALF_UP);
        BigDecimal available = getAvailableBalance();
        if (available.compareTo(scaledAmount) < 0) {
            throw new InsufficientBalanceException(
                "Cannot reserve " + scaledAmount + " from wallet " + this.id + ". Available funds: " + available
            );
        }
        this.reservedBalance = this.reservedBalance.add(scaledAmount);
    }

    public synchronized void releaseReservedFunds(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Release amount must be strictly positive");
        }
        BigDecimal scaledAmount = amount.setScale(4, RoundingMode.HALF_UP);
        if (this.reservedBalance.compareTo(scaledAmount) < 0) {
            throw new IllegalArgumentException(
                "Cannot release " + scaledAmount + ". Currently reserved: " + this.reservedBalance
            );
        }
        this.reservedBalance = this.reservedBalance.subtract(scaledAmount);
    }

    public synchronized void settleReservedDebit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Settlement amount must be strictly positive");
        }
        BigDecimal scaledAmount = amount.setScale(4, RoundingMode.HALF_UP);
        if (this.reservedBalance.compareTo(scaledAmount) < 0 || this.balance.compareTo(scaledAmount) < 0) {
            throw new InsufficientBalanceException(
                "Cannot settle reserved debit of " + scaledAmount + ". Balance: " + this.balance + ", Reserved: " + this.reservedBalance
            );
        }
        this.balance = this.balance.subtract(scaledAmount);
        this.reservedBalance = this.reservedBalance.subtract(scaledAmount);
    }

    public BigDecimal getReservedBalance() {
        return reservedBalance;
    }

    public void setReservedBalance(BigDecimal reservedBalance) {
        this.reservedBalance = reservedBalance != null ? reservedBalance.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }

    public synchronized void credit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be strictly positive");
        }
        BigDecimal scaledAmount = amount.setScale(4, RoundingMode.HALF_UP);
        this.balance = this.balance.add(scaledAmount);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance != null ? balance.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }

    public WalletStatus getStatus() {
        return status;
    }

    public void setStatus(WalletStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Wallet wallet = (Wallet) o;
        return Objects.equals(id, wallet.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
