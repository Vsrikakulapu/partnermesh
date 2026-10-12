package com.partnermesh.enrollment.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EnrollmentRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String partnerId,
        @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Z0-9_-]+") String programCode,
        @NotBlank @Email @Size(max = 254) String contactEmail) {
}
