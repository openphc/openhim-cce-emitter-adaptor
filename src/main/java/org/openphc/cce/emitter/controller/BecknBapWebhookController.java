package org.openphc.cce.emitter.controller;

import com.fasterxml.jackson.databind.JsonNode;

import org.openphc.cce.emitter.model.beckn.BecknAck;
import org.openphc.cce.emitter.service.CceCallbackService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Callbacks from CCE, called by {@code onix-bap} (which appends the action name). HTTP only; the
 * work is in {@link CceCallbackService}. Exists only with {@code beckn.enabled=true}.
 */
@RestController
@ConditionalOnProperty(prefix = "beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BecknBapWebhookController {

    private final CceCallbackService service;

    public BecknBapWebhookController(CceCallbackService service) {
        this.service = service;
    }

    @PostMapping(path = "/beckn/bap-webhook/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BecknAck> receive(@PathVariable String action, @RequestBody JsonNode body) {
        return service.handle(action, body);
    }
}
