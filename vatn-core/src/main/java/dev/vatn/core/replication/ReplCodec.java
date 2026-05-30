package dev.vatn.core.replication;

import dev.vatn.api.replication.VChange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact, binary-safe wire codec for replication RPC payloads. Avoids base64/JSON bloat by writing
 * change values as raw length-prefixed bytes.
 */
final class ReplCodec {

    private ReplCodec() {}

    /** Pull request: set name, the offset to read after, and a batch limit. */
    record PullRequest(String set, long afterOffset, int limit) {}

    /** A batch of feed changes plus the highest feed offset examined (the new watermark). */
    record Batch(String set, long throughOffset, List<VChange> changes) {}

    static byte[] encodePullRequest(PullRequest r) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeUTF(r.set());
            out.writeLong(r.afterOffset());
            out.writeInt(r.limit());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    static PullRequest decodePullRequest(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return new PullRequest(in.readUTF(), in.readLong(), in.readInt());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] encodeBatch(Batch b) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeUTF(b.set());
            out.writeLong(b.throughOffset());
            out.writeInt(b.changes().size());
            for (VChange c : b.changes()) {
                out.writeUTF(c.key());
                out.writeLong(c.version());
                out.writeUTF(c.originNodeId());
                out.writeLong(c.timestamp() != null ? c.timestamp().toEpochMilli() : 0L);
                out.writeBoolean(c.tombstone());
                byte[] v = c.value() != null ? c.value() : new byte[0];
                out.writeInt(v.length);
                out.write(v);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    static Batch decodeBatch(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String set = in.readUTF();
            long through = in.readLong();
            int n = in.readInt();
            List<VChange> changes = new ArrayList<>(Math.max(0, n));
            for (int i = 0; i < n; i++) {
                String key = in.readUTF();
                long version = in.readLong();
                String origin = in.readUTF();
                long ts = in.readLong();
                boolean tombstone = in.readBoolean();
                int len = in.readInt();
                byte[] value = new byte[Math.max(0, len)];
                in.readFully(value);
                changes.add(new VChange(set, key, tombstone ? null : value, version, origin,
                        Instant.ofEpochMilli(ts), tombstone));
            }
            return new Batch(set, through, changes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
