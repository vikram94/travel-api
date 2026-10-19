package com.example.travel.api;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.travel.api.dto.ApiError;
import com.example.travel.domain.ConflictException;
import com.example.travel.domain.InvalidCursorException;
import com.example.travel.domain.NotFoundException;
import com.example.travel.domain.TransactionNotFoundException;
import com.example.travel.domain.TooManyTransactionsException;
import com.example.travel.domain.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Uniform error bodies: { "error", "message", ...extra keys }.
 * Lock waits, deadlocks and query timeouts become 503 busy with Retry-After: 1
 * — never 500 — so clients can safely retry. Framework-level client errors
 * (405, 415, ...) keep their own 4xx status rather than becoming a 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** H2/Postgres lock + serialization failures (SQLState / H2 error codes). */
    private static final Set<String> BUSY_SQL_STATES = Set.of("40001", "HYT00", "HYT01", "55P03");
    private static final Set<Integer> BUSY_ERROR_CODES = Set.of(50100, 50200);

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validation(ValidationException e) {
        var fieldErrors = e.errors().stream()
                .map(error -> Map.of("field", error.field(), "message", error.message()))
                .toList();
        return badRequest(new ApiError("validation_error", e.getMessage(), Map.of("errors", fieldErrors)));
    }

    @ExceptionHandler(TooManyTransactionsException.class)
    ResponseEntity<ApiError> tooManyTransactions(TooManyTransactionsException e) {
        return badRequest(new ApiError("too_many_transactions", e.getMessage(), e.details()));
    }

    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<ApiError> invalidCursor(InvalidCursorException e) {
        return badRequest(new ApiError("invalid_cursor", e.getMessage(), Map.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException e) {
        return badRequest(new ApiError("invalid_json", "request body is not readable JSON", Map.of()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", e.getMessage(), Map.of()));
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ResponseEntity<ApiError> transactionsNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("transaction_not_found", e.getMessage(), e.details()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(e.error(), e.getMessage(), e.details()));
    }

    /** Backstop for the DB-level ON DELETE RESTRICT between a transaction and its invoice items. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException e) {
        log.warn("data integrity violation", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("conflict", "the operation conflicts with existing data", Map.of()));
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiError> concurrency(ConcurrencyFailureException e) {
        return busy(e);
    }

    @ExceptionHandler(QueryTimeoutException.class)
    ResponseEntity<ApiError> queryTimeout(QueryTimeoutException e) {
        return busy(e);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> dataAccess(DataAccessException e) {
        if (isBusy(e)) {
            return busy(e);
        }
        log.error("unhandled data access failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "unexpected server error", Map.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", "no such endpoint", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        if (isBusy(e)) {
            return busy(e);
        }
        if (e instanceof ErrorResponse errorResponse) {
            // Framework-level client errors (405 method not allowed, 415 unsupported
            // media type, ...) keep their own status instead of becoming a 500.
            return status(errorResponse.getStatusCode(), errorResponse);
        }
        log.error("unhandled failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "unexpected server error", Map.of()));
    }

    private ResponseEntity<ApiError> busy(Exception e) {
        log.warn("busy, responding 503: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ApiError("busy", "the service is busy, please retry shortly", Map.of()));
    }

    /** Re-shape a Spring {@link ErrorResponse} into the uniform error body. */
    private static ResponseEntity<ApiError> status(HttpStatusCode status, ErrorResponse errorResponse) {
        String message = errorResponse.getBody().getDetail();
        if (message == null) {
            message = errorResponse.getBody().getTitle();
        }
        if (message == null) {
            message = "request could not be handled";
        }
        return ResponseEntity.status(status).body(new ApiError(codeOf(status), message, Map.of()));
    }

    private static String codeOf(HttpStatusCode status) {
        return status instanceof HttpStatus httpStatus
                ? httpStatus.name().toLowerCase(Locale.ROOT)
                : "request_error";
    }

    private static boolean isBusy(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TransactionTimedOutException) {
                return true;
            }
            if (current instanceof SQLException sql) {
                if (BUSY_ERROR_CODES.contains(sql.getErrorCode())) {
                    return true;
                }
                if (sql.getSQLState() != null && BUSY_SQL_STATES.contains(sql.getSQLState())) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ResponseEntity<ApiError> badRequest(ApiError error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }
}
