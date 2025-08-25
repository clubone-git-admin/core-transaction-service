package io.clubone.transaction.service;

import java.util.UUID;

import io.clubone.transaction.vo.PaymentRequestDTO;

public interface PaymentService {

	UUID processManualPayment(PaymentRequestDTO request);
}
