package org.openphc.cce.emitter.model.beckn;

import java.util.Set;

/** Beckn action names, for metric tags: an unknown action is tagged {@code other}. */
public final class BecknActions {

    private static final Set<String> KNOWN = Set.of(
            "search", "select", "init", "confirm", "status", "track", "cancel", "update", "rating", "rate", "support",
            "on_search", "on_select", "on_init", "on_confirm", "on_status", "on_track", "on_cancel", "on_update",
            "on_rating", "on_rate", "on_support");

    private BecknActions() {
    }

    public static String tag(String action) {
        return action != null && KNOWN.contains(action) ? action : "other";
    }
}
