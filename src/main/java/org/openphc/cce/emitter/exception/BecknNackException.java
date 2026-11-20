package org.openphc.cce.emitter.exception;

import org.openphc.cce.emitter.model.beckn.ErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * A Sygnal {@code Nack}: its code (from {@link ErrorCodes}), HTTP status and text. Thrown inside the
 * {@code sygnal/event/report} endpoint and turned into the {@code Nack} body (spec §3.3).
 */
public class BecknNackException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BecknNackException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /** A {@code Nack} 400: the sender's error, not to be sent again as it is. */
    public static BecknNackException badRequest(String code, String message) {
        return new BecknNackException(code, HttpStatus.BAD_REQUEST, message);
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
