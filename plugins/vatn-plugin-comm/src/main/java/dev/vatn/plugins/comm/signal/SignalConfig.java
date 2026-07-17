package dev.vatn.plugins.comm.signal;

import dev.vatn.api.VAgentMode;

/**
 * Configuration for the Signal channel agent.
 *
 * <p>Requires a running <a href="https://github.com/bbernhard/signal-cli-rest-api">
 * signal-cli-rest-api</a> instance. The phone number must already be registered/linked.
 *
 * <pre>{@code
 * SignalConfig.of("http://localhost:8080", "+49123456789")
 *
 * SignalConfig.of("http://signal-gateway:8080", "+49123456789")
 *     .withPollIntervalMs(3_000)
 *     .withAgentMode(VAgentMode.activePassive())
 * }</pre>
 */
public final class SignalConfig {

    private final String  apiUrl;
    private final String  phoneNumber;
    private final long    pollIntervalMs;
    private final VAgentMode agentMode;

    private SignalConfig(String apiUrl, String phoneNumber, long pollIntervalMs, VAgentMode agentMode) {
        this.apiUrl         = apiUrl;
        this.phoneNumber    = phoneNumber;
        this.pollIntervalMs = pollIntervalMs;
        this.agentMode      = agentMode;
    }

    public static SignalConfig of(String apiUrl, String phoneNumber) {
        return new SignalConfig(apiUrl, phoneNumber, 2_000, VAgentMode.singleton());
    }

    public SignalConfig withPollIntervalMs(long ms) {
        return new SignalConfig(apiUrl, phoneNumber, ms, agentMode);
    }

    public SignalConfig withAgentMode(VAgentMode mode) {
        return new SignalConfig(apiUrl, phoneNumber, pollIntervalMs, mode);
    }

    public String  getApiUrl()         { return apiUrl; }
    public String  getPhoneNumber()    { return phoneNumber; }
    public long    getPollIntervalMs() { return pollIntervalMs; }
    public VAgentMode getAgentMode()   { return agentMode; }
}
