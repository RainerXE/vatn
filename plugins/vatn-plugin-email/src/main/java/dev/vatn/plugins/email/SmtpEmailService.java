package dev.vatn.plugins.email;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/** Jakarta Mail (SMTP) implementation of {@link EmailService}. */
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final EmailConfig config;
    private final Session session;

    public SmtpEmailService(EmailConfig config) {
        this.config = config;
        this.session = buildSession(config);
    }

    @Override
    public void send(EmailMessage message) throws Exception {
        MimeMessage mime = new MimeMessage(session);
        mime.setFrom(new InternetAddress(config.getFrom()));

        for (String to : message.to()) {
            mime.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
        }

        mime.setSubject(message.subject(), "UTF-8");

        if (message.html()) {
            mime.setContent(message.body(), "text/html; charset=UTF-8");
        } else {
            mime.setText(message.body(), "UTF-8");
        }

        Transport.send(mime);
        log.debug("Email sent to {} — subject: {}", message.to(), message.subject());
    }

    private static Session buildSession(EmailConfig config) {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getHost());
        props.put("mail.smtp.port", String.valueOf(config.getPort()));
        props.put("mail.smtp.auth", String.valueOf(config.isAuth()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(config.getTimeoutMs()));
        props.put("mail.smtp.timeout", String.valueOf(config.getTimeoutMs()));

        if (config.isStartTls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        if (config.isSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        }

        if (config.isAuth()) {
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.getUsername(), config.getPassword());
                }
            });
        }
        return Session.getInstance(props);
    }
}
