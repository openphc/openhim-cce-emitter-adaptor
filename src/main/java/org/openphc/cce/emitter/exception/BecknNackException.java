package org.openphc.cce.emitter.exception;

import org.springframework.http.HttpStatus;

/**
 * A Beckn {@code Nack}: its code, HTTP status and text. Thrown inside the Beckn endpoints and
 * turned into the {@code Nack} body (spec §3.3).
 */
public class BecknNackException extends RuntimeException {

    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String NOT_SUPPORTED = "NOT_SUPPORTED";
    public static final String EVENT_REJECTED = "EVENT_REJECTED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String HIE_ERROR = "HIE_ERROR";

    private final String code;
    private final HttpStatus status;

    public BecknNackException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static BecknNackException badRequest(String message) {
        return new BecknNackException(BAD_REQUEST, HttpStatus.BAD_REQUEST, message);
    }

    public static BecknNackException notSupported(String message) {
        return new BecknNackException(NOT_SUPPORTED, HttpStatus.BAD_REQUEST, message);
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
