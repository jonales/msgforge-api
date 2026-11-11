package dev.msgforge.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void constraintViolationsBecome400WithoutLeakingSql() {
        assertMessage(handler.constraint(violation("duplicate key value violates unique constraint \"uk_instance_token\"")),
                400, "This token is already in use.");
        assertMessage(handler.constraint(violation("duplicate key value violates unique constraint \"instance_name_key\"")),
                400, "This instance name is already in use.");
        assertMessage(handler.constraint(violation("insert or update on table \"x\" violates foreign key")),
                400, "The request conflicts with existing data.");
    }

    @Test
    void springExceptionsKeepTheirStatus() {
        var response = handler.unexpected(new ResponseStatusException(HttpStatus.CONFLICT, "já existe"));
        assertMessage(response, 409, "já existe");
        assertThat(response.getBody()).containsEntry("error", "Conflict");
    }

    @Test
    void unknownExceptionsBecome500WithoutDetails() {
        assertMessage(handler.unexpected(new IllegalStateException("segredo interno")), 500, "Internal server error");
    }

    private static DataIntegrityViolationException violation(String sqlMessage) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(sqlMessage));
    }

    @SuppressWarnings("unchecked")
    private static void assertMessage(ResponseEntity<Map<String, Object>> response, int status, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("status", status);
        var inner = (Map<String, Object>) response.getBody().get("response");
        assertThat((List<String>) inner.get("message")).containsExactly(message);
    }
}
