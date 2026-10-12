package com.partnermesh.enrollment.domain;

/** Lifecycle of an enrollment. Transitions only ever leave {@code PENDING}. */
public enum EnrollmentStatus {
    PENDING,
    ACTIVE,
    FAILED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
