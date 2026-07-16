package io.clubone.transaction.config;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounds in-flight work so Tomcat/VT concurrency cannot overwhelm Hikari or a
 * single-instance downstream (payment / agreement) under JMeter-style load.
 */
@Component
public class LoadPressureGuard {

	private final Semaphore paymentHttp;
	private final long paymentHttpWaitMs;

	private final Semaphore finalizeDb;
	private final long finalizeDbWaitMs;

	private final Semaphore finalizeAsync;
	private final long finalizeAsyncWaitMs;

	private final Semaphore clientAgreementHttp;
	private final long clientAgreementHttpWaitMs;

	public LoadPressureGuard(
			@Value("${clubone.load.payment-http.permits:16}") int paymentHttpPermits,
			@Value("${clubone.load.payment-http.wait-ms:2000}") long paymentHttpWaitMs,
			@Value("${clubone.load.finalize-db.permits:20}") int finalizeDbPermits,
			@Value("${clubone.load.finalize-db.wait-ms:2000}") long finalizeDbWaitMs,
			@Value("${clubone.load.finalize-async.permits:8}") int finalizeAsyncPermits,
			@Value("${clubone.load.finalize-async.wait-ms:0}") long finalizeAsyncWaitMs,
			@Value("${clubone.load.client-agreement-http.permits:16}") int caHttpPermits,
			@Value("${clubone.load.client-agreement-http.wait-ms:2000}") long caHttpWaitMs) {
		this.paymentHttp = new Semaphore(Math.max(1, paymentHttpPermits), true);
		this.paymentHttpWaitMs = Math.max(0, paymentHttpWaitMs);
		this.finalizeDb = new Semaphore(Math.max(1, finalizeDbPermits), true);
		this.finalizeDbWaitMs = Math.max(0, finalizeDbWaitMs);
		this.finalizeAsync = new Semaphore(Math.max(1, finalizeAsyncPermits), true);
		this.finalizeAsyncWaitMs = Math.max(0, finalizeAsyncWaitMs);
		this.clientAgreementHttp = new Semaphore(Math.max(1, caHttpPermits), true);
		this.clientAgreementHttpWaitMs = Math.max(0, caHttpWaitMs);
	}

	public <T> T withPaymentHttp(SupplierThrowing<T> action) {
		return withGate(paymentHttp, paymentHttpWaitMs, "payment service", action);
	}

	public <T> T withFinalizeDb(SupplierThrowing<T> action) {
		return withGate(finalizeDb, finalizeDbWaitMs, "finalize DB", action);
	}

	public void withFinalizeAsync(Runnable action) {
		boolean acquired;
		try {
			acquired = finalizeAsyncWaitMs <= 0
					? finalizeAsync.tryAcquire()
					: finalizeAsync.tryAcquire(finalizeAsyncWaitMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			return;
		}
		if (!acquired) {
			// Drop / skip soft side-effects rather than pile up and starve Hikari.
			return;
		}
		try {
			action.run();
		} finally {
			finalizeAsync.release();
		}
	}

	public <T> T withClientAgreementHttp(SupplierThrowing<T> action) {
		return withGate(clientAgreementHttp, clientAgreementHttpWaitMs, "client-agreement service", action);
	}

	private static <T> T withGate(Semaphore gate, long waitMs, String label, SupplierThrowing<T> action) {
		boolean acquired;
		try {
			acquired = waitMs <= 0 ? gate.tryAcquire() : gate.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Interrupted waiting for " + label + " capacity");
		}
		if (!acquired) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					label + " is at capacity; retry shortly");
		}
		try {
			return action.get();
		} catch (RuntimeException re) {
			throw re;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			gate.release();
		}
	}

	@FunctionalInterface
	public interface SupplierThrowing<T> {
		T get() throws Exception;
	}
}
