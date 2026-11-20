package org.openphc.cce.emitter.exception;

import org.openphc.cce.emitter.controller.SygnalEventReportController;
import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.model.beckn.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors of the {@code sygnal/event/report} endpoint that happen before its service runs (a body
 * that is not JSON, a refused content type), answered as a {@code Nack} in the body shape of spec
 * §3.3. Scoped to that controller and ordered first, so {@link GlobalExceptionHandler} stays as it
 * is for {@code /inbound}. Such a request has no readable {@code messageId}, so a new one is used.
 */
@RestControllerAdvice(assignableTypes = SygnalEventReportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BecknExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BecknExceptionHandler.class);

    @ExceptionHandler(BecknNackException.class)
    public ResponseEntity<BecknAck> nack(BecknNackException e) {
        return ResponseEntity.status(e.getStatus()).body(BecknAck.nack(null, e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BecknAck> notJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(BecknAck.nack(null, ErrorCodes.SCH_INVALID_JSON, "The body is not valid JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BecknAck> other(Exception e) {
        if (e instanceof ErrorResponse refused && refused.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(refused.getStatusCode()).body(BecknAck.nack(null,
                    ErrorCodes.SCH_INVALID_JSON, "The request was refused: " + e.getMessage()));
        }
        log.error("A Sygnal request could not be handled", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BecknAck.nack(null,
                ErrorCodes.NET_INTERNAL_ERROR, "The request could not be handled. Send it again later."));
    }
}
