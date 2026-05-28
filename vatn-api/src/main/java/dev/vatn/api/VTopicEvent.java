package dev.vatn.api;

import dev.vatn.api.VatnApi;

import java.time.Instant;

/**
 * A single event stored in a {@link VTopic}.
 *
 * <p>{@code id} is a monotonically increasing integer assigned by the database — suitable for use
 * as a consumer offset cursor. A consumer that has processed event {@code id=42} should seek to
 * offset 42 on restart to resume from the next event.
 */
@VatnApi(since = "1.0-alpha.9")
public record VTopicEvent(
        long    id,
        String  topic,
        String  payload,
        Instant publishedAt
) {}
