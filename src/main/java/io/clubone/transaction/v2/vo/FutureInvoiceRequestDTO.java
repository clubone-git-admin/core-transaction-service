package io.clubone.transaction.v2.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.UUID;

public class FutureInvoiceRequestDTO {

  private int cycleNumber;

  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate billingDate; // optional

  /** Required: same semantics as {@link InvoiceRequest#getApplicationId()}. */
  private UUID applicationId;

  /**
   * Required: {@code locations.levels.level_id} or {@code locations.levels.reference_entity_id}
   * (JSON may use {@code locationId}).
   */
  @JsonAlias("locationId")
  private UUID levelId;

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

  public UUID getApplicationId() {
    return applicationId;
  }

  public void setApplicationId(UUID applicationId) {
    this.applicationId = applicationId;
  }

  public UUID getLevelId() {
    return levelId;
  }

  public void setLevelId(UUID levelId) {
    this.levelId = levelId;
  }
}
