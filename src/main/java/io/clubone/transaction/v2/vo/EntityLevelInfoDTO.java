package io.clubone.transaction.v2.vo;

import java.util.UUID;

public record EntityLevelInfoDTO(
 UUID entityTypeId,
 String entityType,
 UUID entityId,
 String entityName,
 UUID levelId,
 String levelName
) {}

