package com.partnermesh.enrollment.api;

public class DuplicateEnrollmentException extends RuntimeException {

    public DuplicateEnrollmentException(String partnerId, String programCode) {
        super("Partner '" + partnerId + "' is already enrolled in program '" + programCode + "'");
    }
}
