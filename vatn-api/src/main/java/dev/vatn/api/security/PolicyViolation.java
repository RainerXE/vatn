package dev.vatn.api.security;

import dev.vatn.api.VatnApi;

/**
 * Event emitted to the {@code vatn.monitor.security} channel whenever a
 * {@link VPolicyInterjector} returns {@link VPolicyInterjector.Decision#DENY}.
 *
 * <p>Subscribers on {@code vatn.monitor.security} can use this record for
 * audit logging, alerting, or adaptive security responses.
 */
@VatnApi(since = "1.0")
public record PolicyViolation(
    String interjectorId,
    String pluginId,
    String streamId,
    String policyMode
) {
    public String toJson() {
        return "{\"interjectorId\":\"" + interjectorId + "\"" +
               ",\"pluginId\":\"" + pluginId + "\"" +
               ",\"streamId\":\"" + streamId + "\"" +
               ",\"policyMode\":\"" + policyMode + "\"}";
    }
}
