package com.partnermesh.enrollment.provisioning;

/** Outcome of one call to the provisioning service, classified for the retry policy. */
public sealed interface ProvisioningResult {

    record Success(String provisionId) implements ProvisioningResult {
    }

    /** Transient: timeouts, connection errors, 408/425/429 and 5xx. Worth retrying. */
    record RetryableFailure(String reason) implements ProvisioningResult {
    }

    /** The request itself is wrong (other 4xx). Retrying cannot help. */
    record PermanentFailure(String reason) implements ProvisioningResult {
    }
}
