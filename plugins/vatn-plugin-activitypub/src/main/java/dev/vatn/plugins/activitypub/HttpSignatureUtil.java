package dev.vatn.plugins.activitypub;

import java.net.URI;
import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Builds HTTP Signature headers (draft-cavage-http-signatures) for outbound ActivityPub POSTs.
 * Signs over (request-target), host, date, and digest using RSA-SHA256.
 */
final class HttpSignatureUtil {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private HttpSignatureUtil() {}

    static HttpRequest buildSignedPost(URI target, String body, String keyId, PrivateKey privateKey) {
        try {
            String date   = HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
            String digest = "SHA-256=" + Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(body.getBytes()));

            String host   = target.getHost();
            String path   = target.getPath();

            String signingString = "(request-target): post " + path + "\n"
                    + "host: " + host + "\n"
                    + "date: " + date + "\n"
                    + "digest: " + digest;

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(signingString.getBytes());
            String signatureB64 = Base64.getEncoder().encodeToString(sig.sign());

            String signatureHeader = "keyId=\"" + keyId + "\","
                    + "algorithm=\"rsa-sha256\","
                    + "headers=\"(request-target) host date digest\","
                    + "signature=\"" + signatureB64 + "\"";

            return HttpRequest.newBuilder(target)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/activity+json")
                    .header("Date", date)
                    .header("Digest", digest)
                    .header("Signature", signatureHeader)
                    .header("Host", host)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign ActivityPub request to " + target, e);
        }
    }
}
