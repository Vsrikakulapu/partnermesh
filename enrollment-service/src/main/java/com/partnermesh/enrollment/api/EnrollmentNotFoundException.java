package com.partnermesh.enrollment.api;

import java.util.UUID;

public class EnrollmentNotFoundException extends RuntimeException {

    public EnrollmentNotFoundException(UUID id) {
        super("Enrollment " + id + " not found");
    }
}
