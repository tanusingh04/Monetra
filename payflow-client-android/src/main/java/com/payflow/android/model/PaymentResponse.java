package com.payflow.android.model;

public record PaymentResponse(
        PaymentReceipt receipt,
        AllowanceCertificate certificate,
        String senderPublicKey
) {}
