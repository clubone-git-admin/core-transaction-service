package io.clubone.transaction.dao.impl;

import java.time.LocalDate;
import java.util.UUID;

public class SubscriptionInvoiceScheduleRow {
	private UUID invoiceId; // Invoice ID
	private UUID subscriptionInstanceId; // Subscription Instance ID
	private int cycleNumber; // Cycle Number
	private LocalDate paymentDueDate; // Payment Due Date
	private UUID createdBy; // Created By (optional)

	// Constructor
	public SubscriptionInvoiceScheduleRow(UUID invoiceId, UUID subscriptionInstanceId, int cycleNumber,
			LocalDate paymentDueDate, UUID createdBy) {
		this.invoiceId = invoiceId;
		this.subscriptionInstanceId = subscriptionInstanceId;
		this.cycleNumber = cycleNumber;
		this.paymentDueDate = paymentDueDate;
		this.createdBy = createdBy;
	}

	// Getter and Setter for invoiceId
	public UUID getInvoiceId() {
		return invoiceId;
	}

	public void setInvoiceId(UUID invoiceId) {
		this.invoiceId = invoiceId;
	}

	// Getter and Setter for subscriptionInstanceId
	public UUID getSubscriptionInstanceId() {
		return subscriptionInstanceId;
	}

	public void setSubscriptionInstanceId(UUID subscriptionInstanceId) {
		this.subscriptionInstanceId = subscriptionInstanceId;
	}

	// Getter and Setter for cycleNumber
	public int getCycleNumber() {
		return cycleNumber;
	}

	public void setCycleNumber(int cycleNumber) {
		this.cycleNumber = cycleNumber;
	}

	// Getter and Setter for paymentDueDate
	public LocalDate getPaymentDueDate() {
		return paymentDueDate;
	}

	public void setPaymentDueDate(LocalDate paymentDueDate) {
		this.paymentDueDate = paymentDueDate;
	}

	// Getter and Setter for createdBy
	public UUID getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(UUID createdBy) {
		this.createdBy = createdBy;
	}
}
