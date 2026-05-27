package dev.vatn.core.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility for GZIP compression/decompression to optimize federated messaging traffic.
 */
public class CompressionUtils {

    public static byte[] compress(byte[] data) throws IOException {
        if (data == null || data.length == 0) return data;
        ByteArrayOutputStream obj = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(obj)) {
            gzip.write(data);
        }
        return obj.toByteArray();
    }

    public static byte[] decompress(byte[] data) throws IOException {
        if (data == null || data.length == 0) return data;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
        return out.toByteArray();
    }
}
