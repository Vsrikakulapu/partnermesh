package com.partnermesh.provisioning;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Injects failures on demand so retries and recovery can be tested. */
@Component
public class FaultInjector {

    public record Settings(double failureRate, long latencyMs, int failNext, int dropResponseNext) {

        public static final Settings NONE = new Settings(0.0, 0, 0, 0);
    }

    private final AtomicReference<Double> failureRate = new AtomicReference<>(0.0);
    private final AtomicReference<Long> latencyMs = new AtomicReference<>(0L);
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger dropResponseNext = new AtomicInteger();

    public void configure(Settings settings) {
        failureRate.set(Math.max(0.0, Math.min(1.0, settings.failureRate())));
        latencyMs.set(Math.max(0L, settings.latencyMs()));
        failNext.set(Math.max(0, settings.failNext()));
        dropResponseNext.set(Math.max(0, settings.dropResponseNext()));
    }

    public Settings current() {
        return new Settings(failureRate.get(), latencyMs.get(), failNext.get(), dropResponseNext.get());
    }

    /** Called before any work is done. */
    public void beforeWork() {
        long delay = latencyMs.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (consume(failNext)) {
            throw new FaultInjectedException("injected failure (failNext)");
        }
        double rate = failureRate.get();
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            throw new FaultInjectedException("injected failure (failureRate=" + rate + ")");
        }
    }

    /** Called after the work has been committed. */
    public void afterWork() {
        if (consume(dropResponseNext)) {
            throw new FaultInjectedException("injected failure after commit (dropResponseNext)");
        }
    }

    private static boolean consume(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    public static class FaultInjectedException extends RuntimeException {

        public FaultInjectedException(String message) {
            super(message);
        }
    }
}
