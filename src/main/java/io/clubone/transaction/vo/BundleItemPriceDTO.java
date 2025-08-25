package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;

public class BundleItemPriceDTO {
    private String description;
    private UUID itemId;
    private String itemDescription;
    private BigDecimal itemQuantity;
    private BigDecimal itemPrice;     // items.item_price.price
    private UUID taxGroupId;
    private BigDecimal bundlePrice;   // bundles.bundle_price.price
    private Boolean isContinuous;     // biro.is_continuous
    private Integer recurrenceCount;  // birr.recurrence_count

    public BundleItemPriceDTO() {}

    public BundleItemPriceDTO(String description,
                              UUID itemId,
                              String itemDescription,
                              BigDecimal itemQuantity,
                              BigDecimal itemPrice,
                              UUID taxGroupId,
                              BigDecimal bundlePrice,
                              Boolean isContinuous,
                              Integer recurrenceCount) {
        this.description = description;
        this.itemId = itemId;
        this.itemDescription = itemDescription;
        this.itemQuantity = itemQuantity;
        this.itemPrice = itemPrice;
        this.taxGroupId = taxGroupId;
        this.bundlePrice = bundlePrice;
        this.isContinuous = isContinuous;
        this.recurrenceCount = recurrenceCount;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public String getItemDescription() { return itemDescription; }
    public void setItemDescription(String itemDescription) { this.itemDescription = itemDescription; }

    public BigDecimal getItemQuantity() { return itemQuantity; }
    public void setItemQuantity(BigDecimal itemQuantity) { this.itemQuantity = itemQuantity; }

    public BigDecimal getItemPrice() { return itemPrice; }
    public void setItemPrice(BigDecimal itemPrice) { this.itemPrice = itemPrice; }

    public UUID getTaxGroupId() { return taxGroupId; }
    public void setTaxGroupId(UUID taxGroupId) { this.taxGroupId = taxGroupId; }

    public BigDecimal getBundlePrice() { return bundlePrice; }
    public void setBundlePrice(BigDecimal bundlePrice) { this.bundlePrice = bundlePrice; }

    public Boolean getIsContinuous() { return isContinuous; }
    public void setIsContinuous(Boolean isContinuous) { this.isContinuous = isContinuous; }

    public Integer getRecurrenceCount() { return recurrenceCount; }
    public void setRecurrenceCount(Integer recurrenceCount) { this.recurrenceCount = recurrenceCount; }
}
