package dev.vatn.api;

import dev.vatn.api.VatnApi;

/**
 * Handle for an active {@link VTopic} subscription. AutoCloseable for try-with-resources.
 *
 * <pre>{@code
 * try (VTopicSubscription sub = topic.subscribe("my-consumer", event -> handle(event))) {
 *     // subscription active while in block
 * }
 * // subscription stopped, offset saved
 * }</pre>
 */
@VatnApi(since = "1.0-alpha.9")
public interface VTopicSubscription extends AutoCloseable {

    /** Consumer ID this subscription was opened for. */
    String consumerId();

    /** Topic name. */
    String topic();

    /** Last offset delivered to this consumer (the {@code id} of the last event processed). */
    long currentOffset();

    /** Pause delivery without closing. Events accumulate and are replayed on {@link #resume}. */
    void pause();

    /** Resume a paused subscription. */
    void resume();

    /** Stop the subscription and flush the consumer's offset to the database. */
    @Override
    void close();
}
