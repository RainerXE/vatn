package dev.vatn.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight UDP Multicast transport for LAN auto-discovery.
 * Bridges LAN broadcasts to the VDiscovery internal engine.
 *
 * <p>All received payloads are routed through {@link VDiscoveryImpl#ingestPeerPayload(byte[])}
 * so that Ed25519 signature verification, replay protection, and rate-limiting are applied
 * uniformly — regardless of whether a message arrived via OIPC or UDP.
 */
public class UdpDiscoveryTransport {
    private static final Logger logger = LoggerFactory.getLogger(UdpDiscoveryTransport.class);

    private static final String MULTICAST_GROUP = "239.255.42.42";
    private static final int MULTICAST_PORT = 9876;

    private final VDiscoveryImpl discovery;
    private final ExecutorService listenerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vatn-udp-discovery");
        t.setDaemon(true);
        return t;
    });

    /** Held so stop() can close it and unblock socket.receive(). */
    private volatile MulticastSocket activeSocket;

    public UdpDiscoveryTransport(VDiscoveryImpl discovery) {
        this.discovery = discovery;
    }

    public void start() {
        listenerThread.submit(this::listen);
    }

    public void stop() {
        listenerThread.shutdownNow();
        MulticastSocket s = activeSocket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
        try {
            listenerThread.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("[LAN-DISCOVERY] UDP transport stopped.");
    }

    private void listen() {
        logger.info("[LAN-DISCOVERY] Starting UDP Multicast listener on {}:{}", MULTICAST_GROUP, MULTICAST_PORT);

        try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
            activeSocket = socket;
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            socket.joinGroup(new InetSocketAddress(group, MULTICAST_PORT),
                             NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));

            byte[] buffer = new byte[4096];
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                // Route through the verified ingress path — same as OIPC messaging.
                // This enforces Ed25519 signature verification, replay protection, and rate-limiting
                // on every UDP payload before it touches the peer cache.
                byte[] payload = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, payload, 0, packet.getLength());
                discovery.ingestPeerPayload(payload);
            }
        } catch (SocketException e) {
            // Normal closure via stop()
            logger.debug("[LAN-DISCOVERY] UDP socket closed.");
        } catch (IOException e) {
            logger.error("[LAN-DISCOVERY] UDP Listener failed", e);
        } finally {
            activeSocket = null;
        }
    }

    public void broadcast(String message) {
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
            socket.send(packet);
        } catch (IOException e) {
            logger.warn("[LAN-DISCOVERY] Failed to broadcast heartbeat", e);
        }
    }
}
