package io.github.caoy123456.shortlink.web;

import io.github.caoy123456.shortlink.service.InvalidUrlException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into HTTP responses, in one place.
 *
 * <p>Without this, an {@link InvalidUrlException} would escape as a 500 -
 * telling the caller "we broke" when in fact they sent bad input. Getting this
 * distinction right (4xx = your fault, 5xx = my fault) is a small thing that
 * separates a service you can operate from one you cannot.
 *
 * <p>{@link ProblemDetail} is the RFC 9457 standard error shape, built into
 * Spring. Using it rather than a hand rolled {@code {"code":..,"msg":..}} means
 * any standard client already knows how to read our errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidUrlException.class)
    public ProblemDetail handleInvalidUrl(InvalidUrlException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid URL");
        return problem;
    }

    /**
     * Raised by {@code @Valid} when the request body fails its constraints.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationFailure(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("request body is invalid");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation failed");
        return problem;
    }
}
