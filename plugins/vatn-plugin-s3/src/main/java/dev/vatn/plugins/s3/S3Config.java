package dev.vatn.plugins.s3;

public final class S3Config {
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String endpoint;  // null = AWS; set for MinIO / R2 / Spaces

    private S3Config(String region, String bucket, String accessKey, String secretKey, String endpoint) {
        this.region = region; this.bucket = bucket;
        this.accessKey = accessKey; this.secretKey = secretKey; this.endpoint = endpoint;
    }

    public static S3Config aws(String region, String bucket, String accessKey, String secretKey) {
        return new S3Config(region, bucket, accessKey, secretKey, null);
    }

    /** For S3-compatible endpoints: MinIO, Cloudflare R2, DigitalOcean Spaces, etc. */
    public static S3Config compatible(String endpoint, String region, String bucket,
                                      String accessKey, String secretKey) {
        return new S3Config(region, bucket, accessKey, secretKey, endpoint);
    }

    public String getRegion()    { return region; }
    public String getBucket()    { return bucket; }
    public String getAccessKey() { return accessKey; }
    public String getSecretKey() { return secretKey; }
    public String getEndpoint()  { return endpoint; }
    public boolean isCustomEndpoint() { return endpoint != null; }
}
