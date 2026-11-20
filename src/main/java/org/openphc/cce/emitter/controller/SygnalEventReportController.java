package org.openphc.cce.emitter.controller;

import com.fasterxml.jackson.databind.JsonNode;

import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.service.BecknInboundService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code sygnal/event/report} from HMIS systems, forwarded by {@code onix-openhim}'s Sygnal receiver
 * (which uses the action as the path). HTTP only; the work is in {@link BecknInboundService}.
 * Exists only with {@code beckn.enabled=true}.
 */
@RestController
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SygnalEventReportController {

    public static final String PATH = "/sygnal/event/report";

    private final BecknInboundService service;

    public SygnalEventReportController(BecknInboundService service) {
        this.service = service;
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BecknAck> receive(@RequestBody JsonNode body) {
        return service.handle(body);
    }
}
