package com.partnermesh.provisioning;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProvisionController {

    public record ProvisionRequest(@NotNull UUID enrollmentId, @NotBlank String partnerId, @NotBlank String programCode) {
    }

    public record ProvisionResponse(UUID enrollmentId, String provisionId, Instant createdAt, boolean created) {
    }

    private final ProvisionRepository provisions;
    private final FaultInjector faults;
    private final Counter requests;
    private final Counter duplicates;

    public ProvisionController(ProvisionRepository provisions, FaultInjector faults, MeterRegistry metrics) {
        this.provisions = provisions;
        this.faults = faults;
        this.requests = metrics.counter("partnermesh.provisioning.requests");
        this.duplicates = metrics.counter("partnermesh.provisioning.duplicates");
    }

    /**
     * Provision an enrollment. Repeating the call for the same enrollment returns the existing provision
     * (200) instead of creating another one (201).
     */
    @PostMapping("/api/v1/provisions")
    public ResponseEntity<ProvisionResponse> provision(@Valid @RequestBody ProvisionRequest request) {
        requests.increment();
        faults.beforeWork();
        Provision candidate = new Provision(request.enrollmentId(), newProvisionId(), request.partnerId(),
                request.programCode(), Instant.now());
        boolean created = provisions.insertIfAbsent(candidate);
        if (!created) {
            duplicates.increment();
        }
        Provision stored = provisions.find(request.enrollmentId()).orElseThrow();
        faults.afterWork();
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new ProvisionResponse(stored.enrollmentId(), stored.provisionId(), stored.createdAt(), created));
    }

    @GetMapping("/api/v1/provisions/{enrollmentId}")
    public ResponseEntity<ProvisionResponse> get(@PathVariable UUID enrollmentId) {
        return provisions.find(enrollmentId)
                .map(p -> ResponseEntity.ok(new ProvisionResponse(p.enrollmentId(), p.provisionId(), p.createdAt(), false)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/internal/stats")
    public Map<String, Number> stats() {
        return Map.of("provisions", provisions.count(), "requests", requests.count(), "duplicates", duplicates.count());
    }

    @GetMapping("/internal/faults")
    public FaultInjector.Settings faults() {
        return faults.current();
    }

    @PutMapping("/internal/faults")
    public FaultInjector.Settings configureFaults(@RequestBody FaultInjector.Settings settings) {
        faults.configure(settings);
        return faults.current();
    }

    @ExceptionHandler(FaultInjector.FaultInjectedException.class)
    ResponseEntity<ProblemDetail> injected(FaultInjector.FaultInjectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Injected fault");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    private static String newProvisionId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "prv-" + HexFormat.of().formatHex(bytes);
    }
}
