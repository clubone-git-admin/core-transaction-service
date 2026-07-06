package io.clubone.transaction.security;

import org.springframework.stereotype.Component;

@Component("perm")
public class PermissionEvaluator {
  public boolean canCreateItem() { return TenantContext.get().hasRole("ITEM_CREATE") || TenantContext.get().hasRole("Admin"); }
}
