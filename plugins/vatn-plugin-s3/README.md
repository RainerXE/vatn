# vatn-plugin-s3
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Object storage via AWS S3 or any S3-compatible service (MinIO, Cloudflare R2, DigitalOcean Spaces).

## How it works

Uses AWS SDK v2 with the URL-connection HTTP client. The plugin registers two services: `S3Service` for direct, named-key object operations, and `VBlobStore` for content-addressed storage where keys are `sha256:<hex>` digests mapped to S3 object paths `sha256/<hex>`. Range reads use the S3 `Range` HTTP header. The `pin`, `unpin`, `evict`, and `evictToFit` methods of `VBlobStore` are no-ops — use S3 bucket lifecycle rules for retention management. Both the `S3Client` and presigned URL signer are initialised at startup and reused across calls.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-s3</artifactId>
    <version>1.0-alpha.15</version>
</dependency>
```

```java
// AWS S3
VNodeRunner.create()
    .addPlugin(new S3Plugin(S3Config.aws("eu-west-1", "my-bucket", accessKey, secretKey)))
    .run();

// MinIO / compatible
VNodeRunner.create()
    .addPlugin(new S3Plugin(S3Config.compatible("http://minio:9000", "us-east-1", "my-bucket", accessKey, secretKey)))
    .run();
```

## API

```java
public interface S3Service {
    void         upload(String key, byte[] bytes, String contentType);
    void         upload(String key, InputStream stream, long len, String contentType);
    byte[]       download(String key);
    InputStream  downloadStream(String key);
    void         delete(String key);
    List<String> list(String prefix);
    String       presignUrl(String key, Duration expiry);
    boolean      exists(String key);
}

public interface VBlobStore {
    String       putContent(byte[] bytes, String contentType);           // returns "sha256:<hex>"
    String       putContent(InputStream stream, String contentType);
    byte[]       get(String key);
    InputStream  openStream(String key);
    InputStream  openRange(String key, long offset, long length);
    void         put(String key, byte[] bytes, String contentType);
    void         put(String key, InputStream stream, String contentType);
    BlobStat     stat(String key);
    List<String> list(String prefix);
    void         delete(String key);
    boolean      exists(String key);
    void pin(String key);    // no-op
    void unpin(String key);  // no-op
    void evict(String key);  // no-op
    void evictToFit(long bytes); // no-op
    long totalSize();        // returns 0
}
```

```java
S3Service s3 = ctx.service(S3Service.class);

// Upload a file
s3.upload("images/logo.png", pngBytes, "image/png");

// Generate a pre-signed URL valid for 1 hour
String url = s3.presignUrl("images/logo.png", Duration.ofHours(1));

// Content-addressed storage
VBlobStore blobs = ctx.service(VBlobStore.class);
String key = blobs.putContent(pdfBytes, "application/pdf");
// key → "sha256:e3b0c44..."
InputStream in = blobs.openRange(key, 0, 4096);
```

## Configuration

| Option        | Default     | Meaning                                              |
|---------------|-------------|------------------------------------------------------|
| `region`      | —           | AWS region or dummy region for compatible endpoints  |
| `bucket`      | —           | S3 bucket name                                       |
| `accessKey`   | —           | AWS access key ID                                    |
| `secretKey`   | —           | AWS secret access key                                |
| `endpoint`    | AWS default | Custom endpoint URL (MinIO, R2, Spaces, etc.)        |

## Notes

- `S3Config.compatible(endpoint, ...)` sets path-style access, which is required by MinIO and most non-AWS services.
- `VBlobStore.putContent` computes the SHA-256 digest of the content client-side and uses it as the S3 object key; uploading the same bytes twice is idempotent.
- `totalSize()` always returns `0` — use S3 bucket metrics or `list` + `stat` if you need consumed bytes.
- `pin`/`unpin`/`evict` are no-ops by design; configure bucket lifecycle rules directly in your S3-compatible service for automated expiry.
