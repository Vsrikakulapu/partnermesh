package com.partnermesh.enrollment.api;

import com.partnermesh.enrollment.idempotency.IdempotencyKeyReusedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain errors to RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail invalidKey(InvalidIdempotencyKeyException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid idempotency key", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", e.getMessage());
    }

    @ExceptionHandler(DuplicateEnrollmentException.class)
    ProblemDetail duplicate(DuplicateEnrollmentException e) {
        return problem(HttpStatus.CONFLICT, "Already enrolled", e.getMessage());
    }

    @ExceptionHandler(EnrollmentNotFoundException.class)
    ProblemDetail notFound(EnrollmentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
