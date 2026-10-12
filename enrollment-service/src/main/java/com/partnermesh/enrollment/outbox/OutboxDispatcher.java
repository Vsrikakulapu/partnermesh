package com.partnermesh.enrollment.outbox;

import com.partnermesh.enrollment.config.OutboxProperties;
import com.partnermesh.enrollment.domain.Enrollment;
import com.partnermesh.enrollment.domain.EnrollmentRepository;
import com.partnermesh.enrollment.provisioning.ProvisioningClient;
import com.partnermesh.enrollment.provisioning.ProvisioningResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers outbox events to the provisioning service.
 *
 * <p>The network call happens <em>outside</em> any database transaction: claim (short transaction),
 * call, then record the outcome (short transaction). Failure handling:
 * <ul>
 *   <li>transient failure &rarr; reschedule with exponential backoff and jitter</li>
 *   <li>attempts exhausted or permanent failure &rarr; dead-letter the event and fail the enrollment</li>
 *   <li>process crash mid-call &rarr; the lease expires and another instance re-claims the event; the
 *       downstream is idempotent on the enrollment id, so the repeated call is harmless</li>
 * </ul>
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outbox;
    private final EnrollmentRepository enrollments;
    private final ProvisioningClient provisioning;
    private final BackoffPolicy backoff;
    private final OutboxProperties properties;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final Clock clock;

    public OutboxDispatcher(OutboxRepository outbox, EnrollmentRepository enrollments, ProvisioningClient provisioning,
            BackoffPolicy backoff, OutboxProperties properties, TransactionTemplate transactions,
            MeterRegistry metrics, Clock clock) {
        this.outbox = outbox;
        this.enrollments = enrollments;
        this.provisioning = provisioning;
        this.backoff = backoff;
        this.properties = properties;
        this.transactions = transactions;
        this.metrics = metrics;
        this.clock = clock;
        for (String status : List.of("PENDING", "IN_FLIGHT", "DEAD")) {
            Gauge.builder("partnermesh.outbox.events", outbox, repo -> repo.countByStatus(status))
                    .tag("status", status)
                    .description("Outbox events by status")
                    .register(metrics);
        }
    }

    @Scheduled(fixedDelayString = "${partnermesh.outbox.poll-interval-ms:500}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            log.warn("outbox poll failed; will retry on next tick", e);
        }
    }

    /** Claims and processes one batch. Returns the number of events processed. */
    public int dispatchDue() {
        List<OutboxEvent> claimed = outbox.claimDue(clock.instant(), properties.batchSize(), properties.lease());
        for (OutboxEvent event : claimed) {
            process(event);
        }
        return claimed.size();
    }

    void process(OutboxEvent event) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome;
        Optional<Enrollment> enrollment = enrollments.findById(event.aggregateId());
        if (enrollment.isEmpty() || enrollment.get().status().isTerminal()) {
            // Nothing left to do (already completed by an earlier delivery, or the row is gone).
            outbox.markDone(event, clock.instant());
            outcome = "skipped";
        } else {
            ProvisioningResult result;
            try {
                result = provisioning.provision(enrollment.get());
            } catch (RuntimeException e) {
                result = new ProvisioningResult.RetryableFailure(e.toString());
            }
            outcome = switch (result) {
                case ProvisioningResult.Success success -> complete(event, success);
                case ProvisioningResult.RetryableFailure failure -> retryOrGiveUp(event, failure.reason());
                case ProvisioningResult.PermanentFailure failure -> giveUp(event, "permanent: " + failure.reason());
            };
        }
        sample.stop(metrics.timer("partnermesh.outbox.dispatch", "outcome", outcome));
    }

    private String complete(OutboxEvent event, ProvisioningResult.Success success) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            if (outbox.markDone(event, now)) {
                enrollments.activate(event.aggregateId(), success.provisionId(), now);
            }
        });
        log.info("enrollment {} provisioned as {} after {} attempt(s)",
                event.aggregateId(), success.provisionId(), event.attempts());
        return "success";
    }

    private String retryOrGiveUp(OutboxEvent event, String reason) {
        if (event.attempts() >= properties.maxAttempts()) {
            return giveUp(event, "gave up after " + event.attempts() + " attempts: " + reason);
        }
        Instant now = clock.instant();
        Instant next = now.plus(backoff.delay(event.attempts()));
        outbox.reschedule(event, next, reason, now);
        log.info("enrollment {} attempt {} failed ({}); retrying at {}", event.aggregateId(), event.attempts(), reason, next);
        return "retry";
    }

    private String giveUp(OutboxEvent event, String reason) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            if (outbox.markDead(event, reason, now)) {
                enrollments.fail(event.aggregateId(), reason, now);
            }
        });
        log.warn("enrollment {} failed: {}", event.aggregateId(), reason);
        return "dead";
    }
}
