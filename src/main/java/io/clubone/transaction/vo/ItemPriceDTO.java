package io.clubone.transaction.vo;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class ItemPriceDTO {
    private String itemDescription;
    private BigDecimal itemPrice;
    private UUID taxGroupId;
}

