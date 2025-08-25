package io.clubone.transaction.baseobject;

public enum UserRole {

	MEMBER,
	PRACTITIONER,
	MANAGER,
	SYSTEM,
	ADMIN,
	UNKNOWN;

	public static UserRole lookup(String role) {
		if (role == null) {
			return UNKNOWN;
		}
		try {
			return valueOf(role);
		} catch (IllegalArgumentException ex) {
			return UNKNOWN;
		}
	}
}
