package io.clubone.transaction.dao.utils;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

public final class DaoUtils {
	private DaoUtils() {
	}

	public static UUID queryForUuid(JdbcTemplate jdbc, String sql, Object... args) {
		try {
			return jdbc.queryForObject(sql, (rs, rn) -> (UUID) rs.getObject(1), args);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	public static String nz(String s) {
		return s == null ? null : s.trim().isEmpty() ? null : s.trim();
	}
}
