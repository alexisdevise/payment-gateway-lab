package com.alexis.payment.adapter.in.web;

import com.alexis.payment.domain.exception.AmountExceededException;
import com.alexis.payment.domain.exception.ConcurrentPaymentUpdateException;
import com.alexis.payment.domain.exception.DomainException;
import com.alexis.payment.domain.exception.IdempotencyConflictException;
import com.alexis.payment.domain.exception.InvalidStateTransitionException;
import com.alexis.payment.domain.exception.NoPspContractException;
import com.alexis.payment.domain.exception.PaymentNotFoundException;
import com.alexis.payment.domain.exception.PspDeclinedException;
import com.alexis.payment.domain.exception.PspUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/** RFC 7807 for every error. Messages never contain payload or token content. */
@RestControllerAdvice
class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException e) {
        HttpStatus status = switch (e) {
            case PaymentNotFoundException ignored -> HttpStatus.NOT_FOUND;
            case InvalidStateTransitionException ignored -> HttpStatus.CONFLICT;
            case ConcurrentPaymentUpdateException ignored -> HttpStatus.CONFLICT;
            case IdempotencyConflictException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AmountExceededException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NoPspContractException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PspDeclinedException ignored -> HttpStatus.PAYMENT_REQUIRED;
            case PspUnavailableException ignored -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, "2");
            log.warn("PSP unavailable: {}", e.getMessage());
        }
        return response.body(problem(status, e.code(), e.getMessage()));
    }

    /** Value-object validation (bad currency, idempotency key format, base64...). */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleInvalidInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage()));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:payment-gateway:problem:" + code));
        problem.setProperty("code", code);
        return problem;
    }
}
