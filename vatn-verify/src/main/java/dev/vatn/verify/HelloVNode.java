package dev.vatn.verify;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import java.util.logging.Logger;

/**
 * A minimal VATN plugin for standalone verification.
 * Listens for "PING" messages and responds with "PONG".
 */
public class HelloVNode implements VNodePlugin {

    private static final Logger logger = Logger.getLogger(HelloVNode.class.getName());

    @Override
    public void onInitialize(VNodeContext context) {
        logger.log(java.util.logging.Level.INFO, "HelloVNode started on node: {0}", context.getNodeId());
        
        // Subscribe to PING channel
        context.getMessaging().subscribe("verify.ping", payload -> {
            String message = new String(payload);
            logger.log(java.util.logging.Level.INFO, "Received message: {0}", message);
            if ("PING".equals(message)) {
                context.getMessaging().publish("verify.pong", "PONG".getBytes());
            }
        });
    }

    @Override
    public void onShutdown() {
        logger.info("HelloVNode stopping...");
    }

    @Override
    public String getId() {
        return "hello-vnode";
    }

    @Override
    public String getName() {
        return "Hello VATN Plugin";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }
}
