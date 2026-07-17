package dev.vatn.plugins.email;

/**
 * SMTP configuration for the email plugin.
 *
 * <pre>{@code
 * // Gmail with TLS
 * EmailConfig config = EmailConfig
 *     .of("smtp.gmail.com", 587, "user@gmail.com", "app-password", "user@gmail.com")
 *     .withStartTls(true);
 *
 * // Local / dev mailhog
 * EmailConfig config = EmailConfig.of("localhost", 1025, "", "", "noreply@example.com");
 * }</pre>
 */
public final class EmailConfig {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final boolean auth;
    private final boolean startTls;
    private final boolean ssl;
    private final int timeoutMs;

    private EmailConfig(String host, int port, String username, String password, String from,
                        boolean auth, boolean startTls, boolean ssl, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
        this.auth = auth;
        this.startTls = startTls;
        this.ssl = ssl;
        this.timeoutMs = timeoutMs;
    }

    public static EmailConfig of(String host, int port, String username,
                                 String password, String from) {
        boolean hasCredentials = username != null && !username.isBlank();
        return new EmailConfig(host, port, username, password, from,
                hasCredentials, false, false, 10_000);
    }

    /** Enable STARTTLS upgrade (port 587 / standard TLS). */
    public EmailConfig withStartTls(boolean startTls) {
        return new EmailConfig(host, port, username, password, from, auth, startTls, ssl, timeoutMs);
    }

    /** Enable implicit SSL/TLS (port 465). */
    public EmailConfig withSsl(boolean ssl) {
        return new EmailConfig(host, port, username, password, from, auth, startTls, ssl, timeoutMs);
    }

    public EmailConfig withTimeoutMs(int timeoutMs) {
        return new EmailConfig(host, port, username, password, from, auth, startTls, ssl, timeoutMs);
    }

    public String getHost()     { return host; }
    public int getPort()        { return port; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getFrom()     { return from; }
    public boolean isAuth()     { return auth; }
    public boolean isStartTls() { return startTls; }
    public boolean isSsl()      { return ssl; }
    public int getTimeoutMs()   { return timeoutMs; }
}
