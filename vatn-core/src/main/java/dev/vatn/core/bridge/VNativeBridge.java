package dev.vatn.core.bridge;

import dev.vatn.api.VNodeContext;
import dev.vatn.core.VNodeRunner;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.graalvm.nativeimage.c.CContext;

/**
 * The GraalVM Native Image bridge for VATN.
 * This class provides the @CEntryPoint methods required by vatn.h.
 */
@CContext(VContext.class)
public class VNativeBridge {

    // Lazy logger holder. VNativeBridge is @CContext, which GraalVM native-image forces to
    // initialize at build time; a static logback Logger field would therefore be captured into
    // the image heap (and drag in the file appender's FileDescriptor). The holder is a separate
    // class initialized at run time on first use, keeping logback fully run-time initialized.
    private static final class LoggerHolder {
        static final Logger LOGGER = LoggerFactory.getLogger(VNativeBridge.class);
    }
    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    // Opaque handle mapping - Track the Runner to allow proper shutdown
    private static final Map<Long, VNodeRunner> activeRunners = new ConcurrentHashMap<>();
    private static final AtomicLong handleCounter = new AtomicLong(1);

    // Thread-local error storage for diagnostic retrieval
    private static final ThreadLocal<String> lastError = new ThreadLocal<>();
    private static final AtomicBoolean debugEnabled = new AtomicBoolean(false);

    /**
     * Represents the VBuffer struct from vatn.h
     */
    @CStruct("VBuffer")
    public interface VBuffer extends PointerBase {
        @CField("data")
        CCharPointer getData();
        @CField("data")
        void setData(CCharPointer value);

        @CField("size")
        int getSize();
        @CField("size")
        void setSize(int value);
    }

    @CEntryPoint(name = "vatn_node_start")
    public static long startNode(IsolateThread thread, CCharPointer configJson) {
        String config = configJson.isNonNull() ? CTypeConversion.toJavaString(configJson) : "{}";
        return doStartNode(config);
    }

    static long doStartNode(String config) {
        try {
            if (config == null || config.isBlank()) config = "{}";
            if (debugEnabled.get()) logger().debug("[VATN-ABI] Starting node with config: {}", config);
            VNodeRunner runner = VNodeRunner.create(0);
            long handle = handleCounter.getAndIncrement();
            activeRunners.put(handle, runner);
            return handle;
        } catch (Throwable t) {
            captureError(t);
            return 0;
        }
    }

    @CEntryPoint(name = "vatn_node_stop")
    public static void stopNode(IsolateThread thread, long handle) {
        VNodeRunner runner = activeRunners.remove(handle);
        if (runner != null) {
            if (debugEnabled.get()) logger().debug("[VATN-ABI] Stopping node: {}", runner.getContext().getNodeId());
            runner.stop();
        }
    }

    @CEntryPoint(name = "vatn_call")
    public static void call(IsolateThread thread, long handle, CCharPointer service, CCharPointer method, 
                            CCharPointer payloadData, int payloadSize, VBuffer resultContainer) {
        try {
            VNodeRunner runner = activeRunners.get(handle);
            VNodeContext context = (runner != null) ? runner.getContext() : null;
            
            if (context == null) {
                resultContainer.setSize(0);
                return;
            }

            String sName = (service.isNonNull()) ? CTypeConversion.toJavaString(service) : "unknown";
            String mName = (method.isNonNull()) ? CTypeConversion.toJavaString(method) : "unknown";
            
            if (debugEnabled.get()) {
                logger().debug("[VATN-ABI] Call: {}.{} (Payload: {} bytes)", sName, mName, payloadSize);
            }

            // 1. Bounds check (Protection)
            if (payloadSize > 256 * 1024 * 1024) { // 256MB
                throw new IllegalArgumentException("Payload exceeds 256MB safety limit.");
            }

            // 2. Dispatch (OIPC Symmetry) prototype logic
            byte[] response = ("ACK:" + sName + "." + mName).getBytes(StandardCharsets.UTF_8);
            
            // 3. Allocate response (Library allocates)
            // For this prototype, we'll return a C string. 
            // In production, we'd use UnmanagedMemory.
            try (CTypeConversion.CCharPointerHolder holder = CTypeConversion.toCBytes(response)) {
                resultContainer.setData(holder.get());
                resultContainer.setSize(response.length);
            }

        } catch (Throwable t) {
            captureError(t);
            resultContainer.setSize(0);
        }
    }

    @CEntryPoint(name = "vatn_free_buffer")
    public static void freeBuffer(IsolateThread thread, VBuffer buffer) {
        if (buffer.isNonNull() && buffer.getData().isNonNull()) {
            if (debugEnabled.get()) logger().debug("[VATN-ABI] Freeing buffer: {} bytes", buffer.getSize());
        }
    }

    @CEntryPoint(name = "vatn_set_debug")
    public static void setDebug(IsolateThread thread, boolean enabled) {
        debugEnabled.set(enabled);
    }

    @CEntryPoint(name = "vatn_get_diagnostics")
    public static void getDiagnostics(IsolateThread thread, long handle, VBuffer resultContainer) {
        VNodeRunner runner = activeRunners.get(handle);
        VNodeContext context = (runner != null) ? runner.getContext() : null;
        
        String json = context != null ? 
            String.format("{\"nodeId\":\"%s\",\"status\":\"ACTIVE\",\"services\":%d}", 
                context.getNodeId(), activeRunners.size()) : "{}";
        
        try (CTypeConversion.CCharPointerHolder holder = CTypeConversion.toCBytes(json.getBytes(StandardCharsets.UTF_8))) {
            resultContainer.setData(holder.get());
            resultContainer.setSize(json.length());
        }
    }

    @CEntryPoint(name = "vatn_get_last_error")
    public static CCharPointer getLastError(IsolateThread thread) {
        String error = lastError.get();
        if (error == null) return WordFactory.nullPointer();
        
        return CTypeConversion.toCString(error).get(); 
    }

    private static void captureError(Throwable t) {
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        logger().error("[VATN-ABI] {}", msg, t);
        lastError.set(msg);
    }
}
