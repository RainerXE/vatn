package dev.vatn.plugins.s3;

import dev.vatn.api.VBlobStore;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;

/**
 * S3 object-storage plugin — registers an {@link S3Service} and a {@link VBlobStore} backed by
 * the AWS SDK v2.
 *
 * <p>Supports AWS S3 and any S3-compatible endpoint (MinIO, Cloudflare R2, DigitalOcean Spaces).
 *
 * <pre>{@code
 * // AWS
 * node.addPlugin(new S3Plugin(S3Config.aws("eu-west-1", "my-bucket", accessKey, secretKey)));
 *
 * // MinIO / R2 / Spaces
 * node.addPlugin(new S3Plugin(S3Config.compatible("http://localhost:9000",
 *         "us-east-1", "my-bucket", accessKey, secretKey)));
 * }</pre>
 *
 * <p>Plugins that don't need S3-specific features (presign URLs) can depend on the standard
 * {@link VBlobStore} SPI and work transparently across local and S3 backends:
 *
 * <pre>{@code
 * VBlobStore blobs = ctx.getService(VBlobStore.class).orElseThrow();
 * String hash = blobs.putContent(imageStream, "image/jpeg");
 * byte[] thumb = blobs.get("covers/42.jpg");
 * InputStream range = blobs.openRange(hash, 0, 1024);
 * }</pre>
 */
public class S3Plugin implements VNodePlugin {

    private final S3Config config;
    private S3ServiceImpl service;

    public S3Plugin(S3Config config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.s3"; }
    @Override public String getName()    { return "VATN S3 Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        service = new S3ServiceImpl(config);
        // Register under both the S3-specific surface and the standard VBlobStore SPI so that
        // backend-agnostic code (and the replication service) can use whichever abstraction fits.
        ctx.registerService(S3Service.class, service);
        ctx.registerService(VBlobStore.class, service);
    }

    @Override
    public void onShutdown() {
        if (service != null) service.close();
    }
}
