package dev.msgforge.instance;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Estados com os mesmos valores textuais da Evolution ("open", "close", "connecting"). */
public enum ConnectionState {
    OPEN("open"),
    CLOSE("close"),
    CONNECTING("connecting");

    private final String value;

    ConnectionState(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static ConnectionState from(String value) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst()
                .orElse(CLOSE);
    }
}
