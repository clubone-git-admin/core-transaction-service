package io.clubone.transaction.v2.vo;

import java.math.BigDecimal;
import java.util.UUID;

public record CycleBandRef(UUID bandId, BigDecimal unitPrice) {}
