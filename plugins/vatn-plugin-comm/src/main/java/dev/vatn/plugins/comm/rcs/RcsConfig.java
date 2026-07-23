package dev.vatn.plugins.comm.rcs;

import dev.vatn.api.VAgentMode;

/**
 * Configuration for the RCS channel agent.
 *
 * <p>RCS has no universal API — this plugin uses a provider-agnostic model:
 * <ul>
 *   <li><b>Inbound</b>: the provider POSTs webhook events to VATN at {@code webhookPath}.</li>
 *   <li><b>Outbound</b>: VATN POSTs to {@code outboundUrl} with a standard JSON body.</li>
 * </ul>
 *
 * <p>Tested against Twilio, Sinch, and MessageBird RCS APIs. For Google RBM
 * (Google RCS Business Messaging), set {@code provider} to {@code GOOGLE_RBM}
 * and supply {@code apiKey}.
 *
 * <pre>{@code
 * RcsConfig.sinch("+49123456789", "https://rcs.sinch.com/rcs/v1/send", "SERVICE_ID", "API_KEY")
 *
 * RcsConfig.twilio("+49123456789", "ACCOUNT_SID", "AUTH_TOKEN")
 *
 * RcsConfig.custom("+49123456789")
 *     .withOutboundUrl("https://my-rcs-gateway/send")
 *     .withApiKey("secret")
 *     .withWebhookPath("/comm/rcs/webhook")
 *     .withAgentMode(VAgentMode.activePassive())
 * }</pre>
 */
public final class RcsConfig {

    public enum Provider { TWILIO, SINCH, MESSAGEBIRD, GOOGLE_RBM, CUSTOM }

    private final Provider provider;
    private final String   fromNumber;
    private final String   outboundUrl;
    private final String   apiKey;
    private final String   apiSecret;     // Twilio auth token, Sinch service plan id, etc.
    private final String   webhookPath;
    private final String   webhookSecret;
    private final VAgentMode agentMode;

    private RcsConfig(Provider provider, String fromNumber, String outboundUrl,
                      String apiKey, String apiSecret, String webhookPath,
                      String webhookSecret, VAgentMode agentMode) {
        this.provider      = provider;
        this.fromNumber    = fromNumber;
        this.outboundUrl   = outboundUrl;
        this.apiKey        = apiKey;
        this.apiSecret     = apiSecret;
        this.webhookPath   = webhookPath;
        this.webhookSecret = webhookSecret;
        this.agentMode     = agentMode;
    }

    public static RcsConfig twilio(String fromNumber, String accountSid, String authToken) {
        return new RcsConfig(Provider.TWILIO, fromNumber,
            "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json",
            accountSid, authToken, "/comm/rcs/webhook", null, VAgentMode.singleton());
    }

    public static RcsConfig sinch(String fromNumber, String sendUrl, String serviceId, String apiKey) {
        return new RcsConfig(Provider.SINCH, fromNumber, sendUrl,
            serviceId, apiKey, "/comm/rcs/webhook", null, VAgentMode.singleton());
    }

    public static RcsConfig messageBird(String fromNumber, String apiKey) {
        return new RcsConfig(Provider.MESSAGEBIRD, fromNumber,
            "https://rest.messagebird.com/messages",
            apiKey, null, "/comm/rcs/webhook", null, VAgentMode.singleton());
    }

    public static RcsConfig custom(String fromNumber) {
        return new RcsConfig(Provider.CUSTOM, fromNumber, null, null, null,
            "/comm/rcs/webhook", null, VAgentMode.singleton());
    }

    public RcsConfig withOutboundUrl(String url)      { return copy(provider, fromNumber, url, apiKey, apiSecret, webhookPath, webhookSecret, agentMode); }
    public RcsConfig withApiKey(String key)            { return copy(provider, fromNumber, outboundUrl, key, apiSecret, webhookPath, webhookSecret, agentMode); }
    public RcsConfig withApiSecret(String secret)      { return copy(provider, fromNumber, outboundUrl, apiKey, secret, webhookPath, webhookSecret, agentMode); }
    public RcsConfig withWebhookPath(String path)      { return copy(provider, fromNumber, outboundUrl, apiKey, apiSecret, path, webhookSecret, agentMode); }
    public RcsConfig withWebhookSecret(String secret)  { return copy(provider, fromNumber, outboundUrl, apiKey, apiSecret, webhookPath, secret, agentMode); }
    public RcsConfig withAgentMode(VAgentMode mode)    { return copy(provider, fromNumber, outboundUrl, apiKey, apiSecret, webhookPath, webhookSecret, mode); }

    private static RcsConfig copy(Provider provider, String fromNumber, String outboundUrl,
                                  String apiKey, String apiSecret, String webhookPath,
                                  String webhookSecret, VAgentMode agentMode) {
        return new RcsConfig(provider, fromNumber, outboundUrl, apiKey, apiSecret,
            webhookPath, webhookSecret, agentMode);
    }

    public Provider  getProvider()      { return provider; }
    public String    getFromNumber()    { return fromNumber; }
    public String    getOutboundUrl()   { return outboundUrl; }
    public String    getApiKey()        { return apiKey; }
    public String    getApiSecret()     { return apiSecret; }
    public String    getWebhookPath()   { return webhookPath; }
    public String    getWebhookSecret() { return webhookSecret; }
    public VAgentMode getAgentMode()    { return agentMode; }
}
