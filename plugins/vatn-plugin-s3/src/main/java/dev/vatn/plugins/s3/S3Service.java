package dev.vatn.plugins.s3;

import dev.vatn.api.VService;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * S3 object storage service registered by {@link S3Plugin}.
 *
 * <pre>{@code
 * S3Service s3 = ctx.getService(S3Service.class).orElseThrow();
 *
 * s3.upload("reports/2026-05-27.pdf", pdfBytes, "application/pdf");
 * byte[] data = s3.download("reports/2026-05-27.pdf");
 * String url  = s3.presignUrl("reports/2026-05-27.pdf", Duration.ofHours(1));
 * }</pre>
 */
public interface S3Service extends VService {

    void   upload(String key, byte[] data, String contentType);
    void   upload(String key, InputStream stream, long contentLength, String contentType);
    byte[] download(String key);
    InputStream downloadStream(String key);
    void   delete(String key);
    List<String> list(String prefix);
    /** Returns a pre-signed URL valid for the given duration. */
    String presignUrl(String key, Duration validity);
    boolean exists(String key);
}
