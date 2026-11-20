package org.openphc.cce.emitter.model.beckn;

/**
 * The error codes of the Nacks this adaptor answers on {@code /sygnal/event/report}. Sygnal asks for
 * codes from the canonical Beckn v2 list ({@code ErrorCode} in {@code beckn.yaml}); the same set as
 * {@code cce-beckn-service}'s {@code ErrorCodes}. CCE answers with these codes too.
 */
public final class ErrorCodes {

    public static final String CTX_MISSING_FIELD = "CTX_MISSING_FIELD";
    public static final String CTX_INVALID_FIELD = "CTX_INVALID_FIELD";
    public static final String CTX_ACTION_MISMATCH = "CTX_ACTION_MISMATCH";
    public static final String SCH_INVALID_JSON = "SCH_INVALID_JSON";
    public static final String SCH_REQUIRED_FIELD_MISSING = "SCH_REQUIRED_FIELD_MISSING";
    public static final String SCH_SCHEMA_VALIDATION_FAILED = "SCH_SCHEMA_VALIDATION_FAILED";
    public static final String SCH_TYPE_NOT_SUPPORTED = "SCH_TYPE_NOT_SUPPORTED";
    /** The event was refused: by the HIE's pipeline, or by CCE. The sender must not send it again as it is. */
    public static final String BIZ_GENERIC_ERROR = "BIZ_GENERIC_ERROR";
    /** OpenHIM or CCE could not be reached or failed. The sender may try again. */
    public static final String NET_DOWNSTREAM_UNAVAILABLE = "NET_DOWNSTREAM_UNAVAILABLE";
    /** The HIE itself failed (OpenHIM refused its client, an unexpected answer, a bug). */
    public static final String NET_INTERNAL_ERROR = "NET_INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
