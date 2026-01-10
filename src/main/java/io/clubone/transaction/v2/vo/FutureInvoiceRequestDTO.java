package io.clubone.transaction.v2.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

public class FutureInvoiceRequestDTO {

  private int cycleNumber;

  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate billingDate; // optional

  public int getCycleNumber() {
    return cycleNumber;
  }

  public void setCycleNumber(int cycleNumber) {
    this.cycleNumber = cycleNumber;
  }

  public LocalDate getBillingDate() {
    return billingDate;
  }

  public void setBillingDate(LocalDate billingDate) {
    this.billingDate = billingDate;
  }
}
