package com.macro.mall.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Executes cache invalidation after a real transaction commits; never writes to the database. */
public final class CacheInvalidation {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidation.class);

    private CacheInvalidation() { }

    public static void afterCommitOrNow(String scenario, Runnable invalidation) {
        Runnable safeInvalidation = () -> {
            try {
                invalidation.run();
            } catch (RuntimeException failure) {
                try {
                    LOGGER.error("event=CACHE_INVALIDATION_CALLBACK_FAILED scenario={} timestamp={} exception={}",
                        scenario, java.time.Instant.now(), failure.getClass().getName(), failure);
                } catch (Throwable loggingFailure) {
                    // Logging must not change an already committed business outcome.
                }
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeInvalidation.run();
                }
            });
        } else {
            safeInvalidation.run();
        }
    }
}
