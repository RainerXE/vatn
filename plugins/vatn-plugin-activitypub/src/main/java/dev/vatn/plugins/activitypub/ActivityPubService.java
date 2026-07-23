package dev.vatn.plugins.activitypub;

import dev.vatn.api.VService;

/**
 * ActivityPub federation service registered by {@link ActivityPubPlugin}.
 *
 * <p>Enables a VATN node to participate in the Fediverse as a first-class ActivityPub actor.
 * Handles inbound activities via {@code /ap/inbox} and sends signed outbound activities.
 *
 * <pre>{@code
 * ActivityPubService ap = ctx.getService(ActivityPubService.class).orElseThrow();
 *
 * // Send a Create Note activity to a remote inbox
 * String activity = """
 *     {"@context":"https://www.w3.org/ns/activitystreams",
 *      "type":"Create","actor":"%s",
 *      "object":{"type":"Note","content":"Hello Fediverse!"}}
 *     """.formatted(ap.getActorUrl());
 * ap.sendActivity("https://mastodon.social/users/someone/inbox", activity);
 * }</pre>
 */
public interface ActivityPubService extends VService {

    /** Returns the full URL of this node's actor document, e.g. {@code https://example.com/ap/actor}. */
    String getActorUrl();

    /** Returns the PEM-encoded RSA public key used by remote servers to verify signatures. */
    String getPublicKeyPem();

    /**
     * Posts an ActivityPub activity JSON to {@code targetInbox}, signed with this node's private key.
     * Throws {@link RuntimeException} on network or signature failure.
     */
    void sendActivity(String targetInbox, String activityJson);
}
