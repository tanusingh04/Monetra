package com.payflow.api.service;

import com.payflow.core.domain.ledger.EntryType;
import com.payflow.core.domain.ledger.LedgerEntry;
import com.payflow.core.domain.payment.Payment;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public List<LedgerEntry> recordDoubleEntry(Payment payment, Wallet senderWallet, Wallet receiverWallet, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be strictly positive");
        }

        // 1. Debit Entry for Sender
        LedgerEntry debitEntry = new LedgerEntry(
                payment,
                senderWallet,
                EntryType.DEBIT,
                amount,
                senderWallet.getBalance()
        );

        // 2. Credit Entry for Receiver
        LedgerEntry creditEntry = new LedgerEntry(
                payment,
                receiverWallet,
                EntryType.CREDIT,
                amount,
                receiverWallet.getBalance()
        );

        List<LedgerEntry> savedEntries = ledgerEntryRepository.saveAll(List.of(debitEntry, creditEntry));

        log.debug("Recorded double-entry ledger for payment {}: DEBIT wallet {} (balance after: {}), CREDIT wallet {} (balance after: {})",
                payment.getId(), senderWallet.getId(), debitEntry.getBalanceAfter(), receiverWallet.getId(), creditEntry.getBalanceAfter());

        return savedEntries;
    }
}
