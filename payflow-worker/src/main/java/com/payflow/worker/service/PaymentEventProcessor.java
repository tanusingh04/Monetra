package com.payflow.worker.service;

import com.payflow.core.event.PaymentCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProcessor.class);

    public void processPaymentEvent(PaymentCompletedEvent event) {
        log.info("Processing downstream business workflows: Payment {} of {} {} from wallet {} to wallet {} confirmed.",
                event.paymentId(), event.amount(), event.currency(), event.senderWalletId(), event.receiverWalletId());
    }
}
