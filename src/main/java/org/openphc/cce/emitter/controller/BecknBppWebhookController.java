package org.openphc.cce.emitter.controller;

import com.fasterxml.jackson.databind.JsonNode;

import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.service.BecknInboundService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Beckn requests from HMIS systems, called by {@code onix-bpp} (which appends the action name).
 * HTTP only; the work is in {@link BecknInboundService}. Exists only with {@code beckn.enabled=true}.
 */
@RestController
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknBppWebhookController {

    private final BecknInboundService service;

    public BecknBppWebhookController(BecknInboundService service) {
        this.service = service;
    }

    @PostMapping(path = "/beckn/bpp-webhook/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BecknAck> receive(@PathVariable String action, @RequestBody JsonNode body) {
        return service.handle(action, body);
    }
}
