package dev.vatn.plugins.s3;

import dev.vatn.api.VBlobStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * S3-backed implementation of both {@link S3Service} (S3-specific API: presign, streams) and
 * {@link VBlobStore} (the VATN-standard content-addressable blob SPI).
 *
 * <p>VBlobStore semantics on S3:
 * <ul>
 *   <li>Content-addressed puts ({@link #putContent}) hash bytes locally (SHA-256) and upload under
 *       the object key {@code sha256/<hex>} — identical content is stored only once (HEAD check).
 *   <li>Range reads use the S3 {@code Range} header — no local buffering.
 *   <li>Pin/unpin/evictToFit are no-ops: S3 has its own lifecycle policies; VATN's local-cache
 *       eviction model does not apply to a remote durable store. {@link #evict} maps to delete.
 *   <li>{@link #totalSize()} returns 0 — accurate totals require paginated listing; callers should
 *       use S3 Storage Lens or CloudWatch metrics instead.
 * </ul>
 */
class S3ServiceImpl implements S3Service, VBlobStore {

    private static final String CAS_S3_PREFIX  = "sha256/";   // the S3 object-key prefix
    private static final String CAS_VATN_PREFIX = "sha256:";  // the VBlobStore key prefix

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    S3ServiceImpl(S3Config config) {
        this.bucket = config.getBucket();
        AwsBasicCredentials creds = AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey());
        Region region = Region.of(config.getRegion());

        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds));
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds));

        if (config.isCustomEndpoint()) {
            URI endpoint = URI.create(config.getEndpoint());
            builder.endpointOverride(endpoint).forcePathStyle(true);
            presignerBuilder.endpointOverride(endpoint);
        }
        this.client    = builder.build();
        this.presigner = presignerBuilder.build();
    }

    void close() {
        client.close();
        presigner.close();
    }

    // ── Shared helpers (back both interfaces) ────────────────────────────────────

    /** Raw S3 PUT — key used verbatim. */
    private void s3Put(String s3Key, byte[] data, String contentType) {
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(s3Key).contentType(contentType).build(),
                RequestBody.fromBytes(data));
    }

    private void s3Put(String s3Key, InputStream stream, long contentLength, String contentType) {
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(s3Key)
                        .contentType(contentType).contentLength(contentLength).build(),
                RequestBody.fromInputStream(stream, contentLength));
    }

    private byte[] s3Get(String s3Key) {
        return client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(s3Key).build()).asByteArray();
    }

    private InputStream s3GetStream(String s3Key) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(s3Key).build());
    }

    private InputStream s3GetRange(String s3Key, String range) {
        return client.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(s3Key).range(range).build());
    }

    private void s3Delete(String s3Key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(s3Key).build());
    }

    private boolean s3Exists(String s3Key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(s3Key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private List<String> s3List(String prefix) {
        return client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .contents().stream().map(S3Object::key).collect(Collectors.toList());
    }

    // ── S3Service ─────────────────────────────────────────────────────────────

    @Override public void upload(String key, byte[] data, String contentType) {
        s3Put(key, data, contentType);
    }

    @Override public void upload(String key, InputStream stream, long contentLength, String contentType) {
        s3Put(key, stream, contentLength, contentType);
    }

    @Override public byte[]      download(String key)       { return s3Get(key); }
    @Override public InputStream downloadStream(String key) { return s3GetStream(key); }

    @Override public void        delete(String key) { s3Delete(vatnToS3Key(key)); }
    @Override public List<String> list(String prefix){ return s3List(vatnToS3Prefix(prefix)); }
    @Override public boolean     exists(String key) { return s3Exists(vatnToS3Key(key)); }

    @Override
    public String presignUrl(String key, Duration validity) {
        PresignedGetObjectRequest req = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(validity)
                        .getObjectRequest(r -> r.bucket(bucket).key(vatnToS3Key(key)))
                        .build());
        return req.url().toString();
    }

    // ── VBlobStore — content-addressed writes ─────────────────────────────────

    @Override
    public String putContent(byte[] data, String contentType) throws IOException {
        return putContent(new ByteArrayInputStream(data), contentType);
    }

    @Override
    public String putContent(InputStream in, String contentType) throws IOException {
        MessageDigest digest = sha256();
        byte[] data;
        try (DigestInputStream dis = new DigestInputStream(in, digest)) {
            data = dis.readAllBytes();
        }
        String hashHex = HexFormat.of().formatHex(digest.digest());
        String s3Key = CAS_S3_PREFIX + hashHex;
        if (!s3Exists(s3Key)) {
            s3Put(s3Key, data, contentType);
        }
        return CAS_VATN_PREFIX + hashHex;
    }

    // ── VBlobStore — named writes ─────────────────────────────────────────────

    @Override public void put(String key, byte[] data, String contentType) {
        s3Put(vatnToS3Key(key), data, contentType);
    }

    @Override public void put(String key, InputStream in, long contentLength, String contentType) {
        s3Put(vatnToS3Key(key), in, contentLength, contentType);
    }

    // ── VBlobStore — reads ────────────────────────────────────────────────────

    @Override
    public byte[] get(String key) throws IOException {
        try {
            return s3Get(vatnToS3Key(key));
        } catch (NoSuchKeyException e) {
            throw new IOException("No such blob: " + key, e);
        }
    }

    @Override
    public InputStream openStream(String key) throws IOException {
        try {
            return s3GetStream(vatnToS3Key(key));
        } catch (NoSuchKeyException e) {
            throw new IOException("No such blob: " + key, e);
        }
    }

    @Override
    public InputStream openRange(String key, long offset, long length) throws IOException {
        String range = length >= 0
                ? "bytes=" + offset + "-" + (offset + length - 1)
                : "bytes=" + offset + "-";
        try {
            return s3GetRange(vatnToS3Key(key), range);
        } catch (NoSuchKeyException e) {
            throw new IOException("No such blob: " + key, e);
        }
    }

    // ── VBlobStore — metadata ─────────────────────────────────────────────────

    @Override
    public Optional<BlobStat> stat(String key) {
        try {
            HeadObjectResponse head = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(vatnToS3Key(key)).build());
            return Optional.of(new BlobStat(
                    key,
                    head.contentLength() != null ? head.contentLength() : 0L,
                    head.contentType(),
                    null,           // S3 HEAD does not return content hash by default
                    head.lastModified() != null ? head.lastModified() : Instant.EPOCH,
                    null,           // S3 does not track last-accessed time
                    false));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    // ── VBlobStore — pin / evict (no-ops on a remote durable store) ───────────

    @Override public void pin(String key)   { /* S3 lifecycle policies govern retention */ }
    @Override public void unpin(String key) { /* S3 lifecycle policies govern retention */ }

    @Override
    public void evict(String key) throws IOException {
        delete(key); // evicting from remote = deleting the object
    }

    @Override
    public int evictToFit(long targetBytes) {
        return 0; // local-cache eviction has no meaning for a remote store
    }

    @Override
    public long totalSize() {
        return 0L; // use S3 Storage Lens / CloudWatch for storage metrics
    }

    // ── key mapping ───────────────────────────────────────────────────────────

    /**
     * Translates a VBlobStore key to an S3 object key.
     * {@code "sha256:<hex>"} → {@code "sha256/<hex>"}; all other keys pass through unchanged.
     */
    private static String vatnToS3Key(String key) {
        if (key != null && key.startsWith(CAS_VATN_PREFIX)) {
            return CAS_S3_PREFIX + key.substring(CAS_VATN_PREFIX.length());
        }
        return key;
    }

    /**
     * Translates a VBlobStore list prefix to an S3 prefix, handling the CAS namespace.
     * {@code "sha256:"} → {@code "sha256/"}; other prefixes pass through.
     */
    private static String vatnToS3Prefix(String prefix) {
        if (prefix != null && prefix.startsWith(CAS_VATN_PREFIX)) {
            return CAS_S3_PREFIX + prefix.substring(CAS_VATN_PREFIX.length());
        }
        return prefix;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
