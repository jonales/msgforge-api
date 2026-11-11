package dev.msgforge.common;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Formato de erro igual ao da Evolution: { status, error, response: { message: [...] } } */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, List.of(ex.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, Object>> badRequest(BadRequestException ex) {
        return error(HttpStatus.BAD_REQUEST, List.of(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        var messages = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .toList();
        return error(HttpStatus.BAD_REQUEST, messages);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, Object>> invalidParameters(HandlerMethodValidationException ex) {
        var messages = ex.getAllErrors().stream().map(e -> String.valueOf(e.getDefaultMessage())).toList();
        return error(HttpStatus.BAD_REQUEST, messages);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, List.of("Invalid or missing JSON body"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> missingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, List.of(ex.getParameterName() + " is required"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, List.of(ex.getName() + " is invalid"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, List.of(ex.getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, List.of(ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, List.of("Cannot " + ex.getHttpMethod() + " /" + ex.getResourcePath()));
    }

    /**
     * Corrida entre duas requisições: ambas passam pela checagem do serviço e uma esbarra na
     * constraint do banco. Responde como a checagem responderia (400), sem vazar SQL.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> constraint(DataIntegrityViolationException ex) {
        var detail = String.valueOf(ex.getMostSpecificCause().getMessage());
        String message;
        if (detail.contains("uk_instance_token")) {
            message = "This token is already in use.";
        } else if (detail.contains("instance_name_key")) {
            message = "This instance name is already in use.";
        } else {
            log.warn("Violação de integridade não mapeada: {}", detail);
            message = "The request conflicts with existing data.";
        }
        return error(HttpStatus.BAD_REQUEST, List.of(message));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        // Exceções do Spring que já trazem status (ResponseStatusException, upload grande demais...)
        if (ex instanceof ErrorResponse response) {
            var status = response.getStatusCode();
            var detail = response.getBody().getDetail();
            return error(status.value(), reasonOf(status),
                    List.of(detail != null ? detail : reasonOf(status)));
        }
        log.error("Erro não tratado", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, List.of("Internal server error"));
    }

    private static String reasonOf(HttpStatusCode status) {
        var known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Error";
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, List<String> messages) {
        return error(status.value(), status.getReasonPhrase(), messages);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String reason, List<String> messages) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status,
                "error", reason,
                "response", Map.of("message", messages)));
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) { super(message); }
    }
}
