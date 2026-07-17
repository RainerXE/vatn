package dev.vatn.plugins.terminalphone;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;

class TorTransport {

    private final String socksHost;
    private final int    socksPort;

    TorTransport(String socksHost, int socksPort) {
        this.socksHost = socksHost;
        this.socksPort = socksPort;
    }

    Socket connect(String onionAddress, int port) throws IOException {
        Proxy  proxy  = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(socksHost, socksPort));
        Socket socket = new Socket(proxy);
        socket.connect(new InetSocketAddress(onionAddress, port), 30_000);
        return socket;
    }

    ServerSocket listen(int port) throws IOException {
        return new ServerSocket(port);
    }
}
