package io.clubone.transaction.salesadvisor;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import io.clubone.transaction.salesadvisor.SalesAdvisorAssignmentResponse.SalesAdvisorChangeResponse;
import io.clubone.transaction.salesadvisor.SalesAdvisorAssignmentResponse.SalesAdvisorRef;
import io.clubone.transaction.security.AccessContext;
import io.clubone.transaction.security.TenantContext;

@Repository
public class SalesAdvisorDao {

	private static final Logger log = LoggerFactory.getLogger(SalesAdvisorDao.class);

	private static final String HISTORY_SELECT = """
			SELECT
			  c.sales_advisor_change_id,
			  c.invoice_id,
			  c.client_agreement_id,
			  c.previous_user_id,
			  NULLIF(BTRIM(CONCAT_WS(' ', p.first_name, p.last_name)), '') AS previous_name,
			  c.new_user_id,
			  NULLIF(BTRIM(CONCAT_WS(' ', n.first_name, n.last_name)), '') AS new_name,
			  c.changed_by_user_id,
			  NULLIF(BTRIM(CONCAT_WS(' ', b.first_name, b.last_name)), '') AS changed_by_name,
			  c.changed_on,
			  c.source,
			  c.reason,
			  inv.invoice_number AS invoice_number,
			  ca.client_agreement_code AS client_agreement_code
			FROM %s c
			LEFT JOIN access.access_user p ON p.user_id = c.previous_user_id
			LEFT JOIN access.access_user n ON n.user_id = c.new_user_id
			LEFT JOIN access.access_user b ON b.user_id = c.changed_by_user_id
			LEFT JOIN transactions.invoice inv ON inv.invoice_id = c.invoice_id
			LEFT JOIN client_agreements.client_agreement ca ON ca.client_agreement_id = c.client_agreement_id
			WHERE (c.invoice_id = ? AND ? IS NOT NULL)
			   OR (c.client_agreement_id = ? AND ? IS NOT NULL)
			ORDER BY c.changed_on DESC
			""";

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate jdbc;

	public SalesAdvisorAssignmentResponse getForInvoice(UUID invoiceId) {
		InvoiceAdvisorRow row = loadInvoice(invoiceId);
		if (row == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found");
		}
		return toResponse(row.advisorUserId, row.advisorName, row.advisorEmail, invoiceId, row.clientAgreementId);
	}

	public SalesAdvisorAssignmentResponse updateForInvoice(UUID invoiceId, SalesAdvisorUpdateRequest req) {
		if (req == null || req.getSalesAdvisorUserId() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "salesAdvisorUserId is required");
		}
		InvoiceAdvisorRow row = loadInvoice(invoiceId);
		if (row == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found");
		}
		UUID newId = req.getSalesAdvisorUserId();
		UUID previous = row.advisorUserId;
		UUID agreementId = firstNonNull(req.getClientAgreementId(), row.clientAgreementId);
		if (Objects.equals(previous, newId)) {
			return toResponse(newId, nameOf(newId), emailOf(newId), invoiceId, agreementId);
		}

		jdbc.update("""
				UPDATE transactions.invoice
				SET sales_advisor_user_id = ?
				WHERE invoice_id = ?
				""", newId, invoiceId);

		if (agreementId != null) {
			safeUpdate("""
					UPDATE client_agreements.client_agreement
					SET sales_advisor_id = ?
					WHERE client_agreement_id = ?
					""", newId, agreementId);
			safeUpdate("""
					UPDATE transactions.invoice
					SET sales_advisor_user_id = ?
					WHERE client_agreement_id = ?
					  AND invoice_id <> ?
					""", newId, agreementId, invoiceId);
			safeUpdate("""
					UPDATE client_agreements.client_agreement
					SET sales_advisor_id = ?
					WHERE client_agreement_id IN (
					  SELECT DISTINCT ie.client_agreement_id
					  FROM transactions.invoice_entity ie
					  WHERE ie.invoice_id = ?
					    AND ie.client_agreement_id IS NOT NULL
					)
					""", newId, invoiceId);
		}

		writeAudit(invoiceId, agreementId, previous, newId, resolveChangedBy(req),
				blankToNull(req.getSource(), "UI"), blankToNull(req.getReason(), null));
		return toResponse(newId, nameOf(newId), emailOf(newId), invoiceId, agreementId);
	}

	public void recordInitial(UUID invoiceId, UUID clientAgreementId, UUID advisorUserId, UUID changedByUserId,
			String source) {
		if (advisorUserId == null) {
			return;
		}
		writeAudit(invoiceId, clientAgreementId, null, advisorUserId, changedByUserId,
				blankToNull(source, "POS"), null);
	}

	private InvoiceAdvisorRow loadInvoice(UUID invoiceId) {
		try {
			return jdbc.queryForObject("""
					SELECT
					  i.invoice_id,
					  i.client_agreement_id,
					  i.sales_advisor_user_id,
					  NULLIF(BTRIM(CONCAT_WS(' ', au.first_name, au.last_name)), '') AS sales_advisor_name,
					  au.email AS sales_advisor_email
					FROM transactions.invoice i
					LEFT JOIN access.access_user au ON au.user_id = i.sales_advisor_user_id
					WHERE i.invoice_id = ?
					""", (rs, n) -> {
				InvoiceAdvisorRow r = new InvoiceAdvisorRow();
				r.invoiceId = rs.getObject("invoice_id", UUID.class);
				r.clientAgreementId = rs.getObject("client_agreement_id", UUID.class);
				r.advisorUserId = rs.getObject("sales_advisor_user_id", UUID.class);
				r.advisorName = rs.getString("sales_advisor_name");
				r.advisorEmail = rs.getString("sales_advisor_email");
				return r;
			}, invoiceId);
		} catch (EmptyResultDataAccessException ex) {
			return null;
		}
	}

	private SalesAdvisorAssignmentResponse toResponse(UUID advisorUserId, String name, String email, UUID invoiceId,
			UUID clientAgreementId) {
		SalesAdvisorAssignmentResponse resp = new SalesAdvisorAssignmentResponse();
		resp.setSalesAdvisorUserId(advisorUserId);
		resp.setSalesAdvisorName(name);
		resp.setSalesAdvisorEmail(email);
		resp.setInvoiceId(invoiceId);
		resp.setClientAgreementId(clientAgreementId);
		if (advisorUserId != null) {
			NameParts parts = splitName(name);
			SalesAdvisorRef ref = new SalesAdvisorRef();
			ref.setUserId(advisorUserId);
			ref.setSalesAdvisorUserId(advisorUserId);
			ref.setFirstName(parts.first);
			ref.setLastName(parts.last);
			ref.setFullName(name);
			ref.setSalesAdvisorName(name);
			ref.setEmail(email);
			ref.setSalesAdvisorEmail(email);
			resp.setSalesAdvisor(ref);
		}
		resp.setHistory(loadHistory(invoiceId, clientAgreementId));
		return resp;
	}

	private List<SalesAdvisorChangeResponse> loadHistory(UUID invoiceId, UUID clientAgreementId) {
		Map<UUID, SalesAdvisorChangeResponse> byId = new LinkedHashMap<>();
		addHistory(byId, "transactions.sales_advisor_change", invoiceId, clientAgreementId);
		addHistory(byId, "client_agreements.sales_advisor_change", invoiceId, clientAgreementId);
		List<SalesAdvisorChangeResponse> list = new ArrayList<>(byId.values());
		list.sort((a, b) -> {
			OffsetDateTime at = a.getChangedOn();
			OffsetDateTime bt = b.getChangedOn();
			if (at == null && bt == null) {
				return 0;
			}
			if (at == null) {
				return 1;
			}
			if (bt == null) {
				return -1;
			}
			return bt.compareTo(at);
		});
		return list;
	}

	private void addHistory(Map<UUID, SalesAdvisorChangeResponse> byId, String table, UUID invoiceId,
			UUID clientAgreementId) {
		try {
			List<SalesAdvisorChangeResponse> rows = jdbc.query(HISTORY_SELECT.formatted(table), (rs, n) -> {
				SalesAdvisorChangeResponse e = new SalesAdvisorChangeResponse();
				e.setSalesAdvisorChangeId(rs.getObject("sales_advisor_change_id", UUID.class));
				e.setInvoiceId(rs.getObject("invoice_id", UUID.class));
				e.setInvoiceNumber(rs.getString("invoice_number"));
				e.setClientAgreementId(rs.getObject("client_agreement_id", UUID.class));
				e.setClientAgreementCode(rs.getString("client_agreement_code"));
				e.setPreviousSalesAdvisorUserId(rs.getObject("previous_user_id", UUID.class));
				e.setPreviousSalesAdvisorName(rs.getString("previous_name"));
				e.setNewSalesAdvisorUserId(rs.getObject("new_user_id", UUID.class));
				e.setNewSalesAdvisorName(rs.getString("new_name"));
				e.setChangedByUserId(rs.getObject("changed_by_user_id", UUID.class));
				e.setChangedByName(rs.getString("changed_by_name"));
				Timestamp ts = rs.getTimestamp("changed_on");
				if (ts != null) {
					e.setChangedOn(ts.toInstant().atOffset(ZoneOffset.UTC));
				}
				e.setSource(rs.getString("source"));
				e.setReason(rs.getString("reason"));
				return e;
			}, invoiceId, invoiceId, clientAgreementId, clientAgreementId);
			for (SalesAdvisorChangeResponse e : rows) {
				if (e.getSalesAdvisorChangeId() != null) {
					byId.putIfAbsent(e.getSalesAdvisorChangeId(), e);
				}
			}
		} catch (Exception ex) {
			log.warn("Sales advisor history read skipped for {}: {}", table, ex.getMessage());
		}
	}

	private void writeAudit(UUID invoiceId, UUID clientAgreementId, UUID previous, UUID newId, UUID changedBy,
			String source, String reason) {
		UUID changeId = UUID.randomUUID();
		Timestamp changedOn = Timestamp.from(java.time.Instant.now());
		Object[] args = { changeId, invoiceId, clientAgreementId, previous, newId, changedBy, changedOn, source,
				reason };
		safeUpdate("""
				INSERT INTO transactions.sales_advisor_change (
				  sales_advisor_change_id, invoice_id, client_agreement_id,
				  previous_user_id, new_user_id, changed_by_user_id, changed_on, source, reason
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", args);
		safeUpdate("""
				INSERT INTO client_agreements.sales_advisor_change (
				  sales_advisor_change_id, invoice_id, client_agreement_id,
				  previous_user_id, new_user_id, changed_by_user_id, changed_on, source, reason
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", args);
	}

	private void safeUpdate(String sql, Object... args) {
		try {
			jdbc.update(sql, args);
		} catch (Exception ex) {
			log.warn("Sales advisor SQL skipped: {} — {}", sql.lines().findFirst().orElse("update"), ex.getMessage());
		}
	}

	private UUID resolveChangedBy(SalesAdvisorUpdateRequest req) {
		if (req.getChangedByUserId() != null) {
			return req.getChangedByUserId();
		}
		TenantContext ctx = TenantContext.get();
		if (ctx != null) {
			return ctx.userId();
		}
		try {
			return AccessContext.actorUserId();
		} catch (Exception ex) {
			return null;
		}
	}

	private String nameOf(UUID userId) {
		UserRow u = loadUser(userId);
		return u == null ? null : u.name;
	}

	private String emailOf(UUID userId) {
		UserRow u = loadUser(userId);
		return u == null ? null : u.email;
	}

	private UserRow loadUser(UUID userId) {
		if (userId == null) {
			return null;
		}
		try {
			return jdbc.queryForObject("""
					SELECT
					  NULLIF(BTRIM(CONCAT_WS(' ', first_name, last_name)), '') AS full_name,
					  email
					FROM access.access_user
					WHERE user_id = ?
					""", (rs, n) -> {
				UserRow u = new UserRow();
				u.name = rs.getString("full_name");
				u.email = rs.getString("email");
				return u;
			}, userId);
		} catch (EmptyResultDataAccessException ex) {
			return null;
		}
	}

	private static UUID firstNonNull(UUID a, UUID b) {
		return a != null ? a : b;
	}

	private static String blankToNull(String v, String fallback) {
		if (v == null || v.isBlank()) {
			return fallback;
		}
		return v.trim();
	}

	private static NameParts splitName(String name) {
		NameParts p = new NameParts();
		if (name == null || name.isBlank()) {
			return p;
		}
		String trimmed = name.trim();
		int sp = trimmed.indexOf(' ');
		if (sp < 0) {
			p.first = trimmed;
			return p;
		}
		p.first = trimmed.substring(0, sp);
		p.last = trimmed.substring(sp + 1).trim();
		return p;
	}

	private static final class InvoiceAdvisorRow {
		UUID invoiceId;
		UUID clientAgreementId;
		UUID advisorUserId;
		String advisorName;
		String advisorEmail;
	}

	private static final class UserRow {
		String name;
		String email;
	}

	private static final class NameParts {
		String first;
		String last;
	}
}
