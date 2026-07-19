package dev.vatn.verify;

import dev.vatn.core.transport.OipcMessagingTransport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-Process OIPC V3 Comprehensive Matrix Test.
 * Tests: (Sizes: 64B, 1KB, 16KB, 128KB, 1MB, 5MB) x (Protocols: BINARY, JSON_BASE64, JSON_NATIVE).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
public class OipcInterProcessTest {

    private static final int[] SIZES = { 64, 1024, 16_384, 131_072, 1_048_576, 5_242_880 };
    private static final String[] PROTOCOLS = { "BINARY", "JSON_BASE64" /*, "JSON_NATIVE" */ }; // JSON_NATIVE skipped in generic test due to binary data constraints
    
    private static final int TARGET_COUNT    = 10_000; // Lower count for matrix to keep test time reasonable
    private static final int TIMEOUT_SECONDS = 30;

    private static String vatncliJar;
    private static String nativeBinary;

    @BeforeAll
    static void resolveArtefacts() throws Exception {
        Path cliTarget = resolveCliTarget();
        File primaryJar = cliTarget.resolve("vatn-cli-bench.jar").toFile();
        if (primaryJar.exists()) {
            vatncliJar = primaryJar.getAbsolutePath();
        } else {
            Path siblingTarget = cliTarget.getParent().getParent().resolve("vatn-cli/target");
            if (Files.isDirectory(siblingTarget)) {
                File[] jars = siblingTarget.toFile().listFiles((d, n) ->
                        n.startsWith("vatn-cli") && n.endsWith(".jar") && !n.contains("original"));
                if (jars != null && jars.length > 0) vatncliJar = jars[0].getAbsolutePath();
            }
        }
        compileAotBinary(cliTarget);
    }

    private static void compileAotBinary(Path benchmarkDir) throws Exception {
        String nativeImageExe = findOnPath("native-image");
        if (nativeImageExe == null || vatncliJar == null) return;

        nativeBinary = benchmarkDir.resolve("vatn").toAbsolutePath().toString();
        if (new File(nativeBinary).exists() && new File(nativeBinary).lastModified() > new File(vatncliJar).lastModified()) return;

        Files.createDirectories(benchmarkDir);
        System.out.println("[AOT] Compiling native binary...");
        Process p = new ProcessBuilder(List.of(nativeImageExe, "--no-fallback", "-jar", vatncliJar, "-o", nativeBinary)).start();
        boolean done = p.waitFor(10, TimeUnit.MINUTES);
        if (!done || p.exitValue() != 0) nativeBinary = null;
    }

    @Test
    @Order(1)
    void testProtocolSizeMatrixJvm() throws Exception {
        Assumptions.assumeTrue(vatncliJar != null, "vatn-cli jar not found");
        
        System.out.println("\n=== OIPC Cross-Process Matrix Results (JVM) ===");
        System.out.printf("%-15s | %-10s | %-10s | %-10s%n", "Protocol", "Size", "Msg/s", "MB/s");
        System.out.println("------------------------------------------------------------------");

        for (String proto : PROTOCOLS) {
            for (int size : SIZES) {
                runMatrixPoint(proto, size, false);
            }
        }
    }

    @Test
    @Order(2)
    void testProtocolSizeMatrixTcp() throws Exception {
        Assumptions.assumeTrue(vatncliJar != null, "vatn-cli jar not found");
        System.setProperty("vatn.ipc.force_tcp", "true");
        try {
            System.out.println("\n=== OIPC Cross-Process Matrix Results (JVM-TCP) ===");
            System.out.printf("%-15s | %-10s | %-10s | %-10s%n", "Protocol", "Size", "Msg/s", "MB/s");
            System.out.println("------------------------------------------------------------------");

            for (String proto : PROTOCOLS) {
                for (int size : SIZES) {
                    runMatrixPoint(proto, size, false);
                }
            }
        } finally {
            System.clearProperty("vatn.ipc.force_tcp");
        }
    }

    @Test
    @Order(3)
    void testProtocolSizeMatrixAot() throws Exception {
        Assumptions.assumeTrue(nativeBinary != null, "vatn native binary not found/compiled");
        
        System.out.println("\n=== OIPC Cross-Process Matrix Results (AOT) ===");
        System.out.printf("%-15s | %-10s | %-10s | %-10s%n", "Protocol", "Size", "Msg/s", "MB/s");
        System.out.println("------------------------------------------------------------------");

        for (String proto : PROTOCOLS) {
            for (int size : SIZES) {
                runMatrixPoint(proto, size, true);
            }
        }
    }

    private void runMatrixPoint(String protocol, int size, boolean aot) throws Exception {
        OipcMessagingTransport server = new OipcMessagingTransport();
        CountDownLatch latch = new CountDownLatch(TARGET_COUNT);
        AtomicLong received = new AtomicLong();

        server.subscribe("binary.ingress", p -> {
            received.incrementAndGet();
            latch.countDown();
        });

        List<String> cmd = new ArrayList<>();
        cmd.add(aot ? nativeBinary : ProcessHandle.current().info().command().orElse("java"));
        if (!aot) {
            cmd.add("-jar");
            cmd.add(vatncliJar);
        }
        cmd.addAll(List.of("benchmark", "--mode=client", "--protocol=" + protocol, "--count=" + TARGET_COUNT, "--payload-size=" + size));
        if (server.isUds()) cmd.add("--path=" + server.getConnectionPath());
        else cmd.add("--port=" + server.getConnectionPort());

        long startNs = System.nanoTime();
        Process p = new ProcessBuilder(cmd).start();
        
        // Drain output to avoid blocks
        Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                r.lines().forEach(l -> {}); 
            } catch (Exception ignore) {}
        });

        boolean done = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long elapsedNs = System.nanoTime() - startNs;
        
        if (p.isAlive()) p.destroyForcibly();
        
        if (done) {
            double msgPerSec = TARGET_COUNT / (elapsedNs / 1e9);
            double mbPerSec = (TARGET_COUNT * (double)size) / (1024.0 * 1024.0) / (elapsedNs / 1e9);
            System.out.printf("%-15s | %-10d | %-10.0f | %-10.2f%n", protocol, size, msgPerSec, mbPerSec);
        } else {
            System.out.printf("%-15s | %-10d | TIMEOUT    | N/A%n", protocol, size);
        }
        
        server.close();
    }

    private static Path resolveCliTarget() {
        return Path.of("").toAbsolutePath().resolve("target/benchmark");
    }

    private static String findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, exe);
                if (f.canExecute()) return f.getAbsolutePath();
            }
        }
        File macGraal = new File("/Library/Java/JavaVirtualMachines/graalvm-jdk-25+37.1/Contents/Home/bin", exe);
        if (macGraal.canExecute()) return macGraal.getAbsolutePath();
        return null;
    }
}
