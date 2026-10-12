package com.partnermesh.enrollment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.partnermesh.enrollment.api.DuplicateEnrollmentException;
import com.partnermesh.enrollment.api.EnrollmentRequest;
import com.partnermesh.enrollment.api.EnrollmentResponse;
import com.partnermesh.enrollment.api.StoredResponse;
import com.partnermesh.enrollment.domain.Enrollment;
import com.partnermesh.enrollment.domain.EnrollmentRepository;
import com.partnermesh.enrollment.domain.EnrollmentStatus;
import com.partnermesh.enrollment.idempotency.IdempotencyKeyReusedException;
import com.partnermesh.enrollment.idempotency.IdempotencyRecord;
import com.partnermesh.enrollment.idempotency.IdempotencyRepository;
import com.partnermesh.enrollment.idempotency.RequestFingerprint;
import com.partnermesh.enrollment.outbox.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts enrollments.
 *
 * <p>One local transaction writes the idempotency record, the enrollment and the outbox event. Either
 * all three commit or none do, so there is never an enrollment without a pending provisioning request,
 * and never a provisioning request for an enrollment that does not exist. The cross-service step
 * (provisioning) happens later, asynchronously, from the outbox.
 */
@Service
public class EnrollmentService {

    static final int ACCEPTED = 202;

    private final EnrollmentRepository enrollments;
    private final IdempotencyRepository idempotency;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final Counter replays;

    public EnrollmentService(EnrollmentRepository enrollments, IdempotencyRepository idempotency, OutboxRepository outbox,
            ObjectMapper json, Clock clock, MeterRegistry metrics) {
        this.enrollments = enrollments;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.replays = Counter.builder("partnermesh.idempotency.replays")
                .description("Requests answered from a stored idempotent response")
                .register(metrics);
    }

    @Transactional
    public StoredResponse enroll(String idempotencyKey, EnrollmentRequest request) {
        String hash = RequestFingerprint.of(request.partnerId(), request.programCode(), request.contactEmail());

        Optional<IdempotencyRecord> existing = idempotency.find(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        Instant now = clock.instant();
        if (!idempotency.claim(idempotencyKey, hash, now)) {
            // A concurrent request with the same key committed while we waited on the unique index.
            return replay(idempotency.find(idempotencyKey).orElseThrow(), hash);
        }

        Enrollment enrollment = new Enrollment(UUID.randomUUID(), request.partnerId().trim(), request.programCode().trim(),
                request.contactEmail().trim(), EnrollmentStatus.PENDING, null, null, now, now);
        if (!enrollments.insertIfAbsent(enrollment)) {
            throw new DuplicateEnrollmentException(enrollment.partnerId(), enrollment.programCode());
        }
        outbox.append(enrollment.id(), OutboxRepository.PROVISION_REQUESTED, toJson(enrollment.id()), now);

        String body = toJson(EnrollmentResponse.from(enrollment));
        idempotency.complete(idempotencyKey, ACCEPTED, body, enrollment.id());
        return new StoredResponse(ACCEPTED, body, enrollment.id(), false);
    }

    @Transactional(readOnly = true)
    public Optional<Enrollment> find(UUID id) {
        return enrollments.findById(id);
    }

    private StoredResponse replay(IdempotencyRecord record, String hash) {
        if (!record.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException(record.key());
        }
        replays.increment();
        UUID enrollmentId = null;
        try {
            enrollmentId = UUID.fromString(json.readTree(record.responseBody()).path("id").asText());
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            // the stored body is returned verbatim either way
        }
        return new StoredResponse(record.responseStatus(), record.responseBody(), enrollmentId, true);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
