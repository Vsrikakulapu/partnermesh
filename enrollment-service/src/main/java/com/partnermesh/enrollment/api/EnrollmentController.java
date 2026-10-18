package com.partnermesh.enrollment.api;

import com.partnermesh.enrollment.EnrollmentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enrollments")
public class EnrollmentController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    private final EnrollmentService service;

    public EnrollmentController(EnrollmentService service) {
        this.service = service;
    }

    /** Create an enrollment. Repeating a request with the same key returns the original response. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> enroll(
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody EnrollmentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("The Idempotency-Key header is required");
        }
        if (!KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must be 8-128 characters of [A-Za-z0-9_-]");
        }
        StoredResponse response = service.enroll(idempotencyKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED, Boolean.toString(response.replayed()));
        if (response.enrollmentId() != null) {
            builder.location(URI.create("/api/v1/enrollments/" + response.enrollmentId()));
        }
        return builder.body(response.body());
    }

    @GetMapping("/{id}")
    public EnrollmentResponse get(@PathVariable UUID id) {
        return service.find(id).map(EnrollmentResponse::from).orElseThrow(() -> new EnrollmentNotFoundException(id));
    }
}
