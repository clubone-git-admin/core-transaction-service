package io.clubone.transaction.helper;

import io.clubone.transaction.config.LoadPressureGuard;
import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async hydration of {@code clients.client_dashboard_proj}.
 * DB triggers may only {@code pg_notify}; finalize must refresh explicitly — never on the request thread.
 */
@Component
public class ClientDashboardProjectionRefresher {

	private static final Logger log = LoggerFactory.getLogger(ClientDashboardProjectionRefresher.class);

	private static final ExecutorService REFRESH_EXEC = Executors.newVirtualThreadPerTaskExecutor();

	private final TransactionDAO transactionDAO;
	private final LoadPressureGuard loadPressureGuard;

	public ClientDashboardProjectionRefresher(TransactionDAO transactionDAO, LoadPressureGuard loadPressureGuard) {
		this.transactionDAO = transactionDAO;
		this.loadPressureGuard = loadPressureGuard;
	}

	/**
	 * Schedules projection refresh after the current transaction commits.
	 * Resolution + {@code refresh_client_dashboard_proj} run only on the async executor.
	 */
	public void scheduleRefreshAfterCommit(UUID invoiceId, UUID clientRoleId, UUID applicationId) {
		if (invoiceId == null && clientRoleId == null) {
			return;
		}
		final TenantContext tenantCtx = TenantContext.get();
		final UUID invoiceIdCapture = invoiceId;
		final UUID clientRoleIdCapture = clientRoleId;
		final UUID applicationIdCapture = applicationId;

		Runnable async = () -> REFRESH_EXEC.execute(() -> loadPressureGuard.withFinalizeAsync(() -> {
			TenantContext previous = TenantContext.get();
			try {
				if (tenantCtx != null) {
					TenantContext.set(tenantCtx);
				}
				refreshNow(invoiceIdCapture, clientRoleIdCapture, applicationIdCapture);
			} catch (Exception ex) {
				log.warn("Async client_dashboard_proj refresh failed invoiceId={} clientRoleId={}: {}",
						invoiceIdCapture, clientRoleIdCapture, ex.getMessage(), ex);
			} finally {
				if (previous != null) {
					TenantContext.set(previous);
				} else {
					TenantContext.clear();
				}
			}
		}));

		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					async.run();
				}
			});
		} else {
			async.run();
		}
	}

	private void refreshNow(UUID invoiceId, UUID clientRoleId, UUID applicationId) {
		UUID rid = clientRoleId;
		if (rid == null && invoiceId != null) {
			rid = transactionDAO.findClientRoleIdByInvoiceId(invoiceId, applicationId).orElse(null);
		}
		if (rid == null) {
			log.debug("Skip client_dashboard_proj refresh: no clientRoleId for invoiceId={}", invoiceId);
			return;
		}
		transactionDAO.refreshClientDashboardProjection(rid);
		log.info("client_dashboard_proj refreshed async for clientRoleId={}", rid);
	}
}
