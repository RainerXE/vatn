package dev.vatn.plugins.activitypub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.workflow.VClaimOptions;
import dev.vatn.api.workflow.VNamedQueue;
import dev.vatn.api.workflow.VQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * ActivityPub plugin — makes this VATN node a Fediverse actor.
 *
 * <p>Registers the following routes:
 * <ul>
 *   <li>{@code GET  /.well-known/webfinger?resource=acct:username@domain}</li>
 *   <li>{@code GET  /ap/actor} — Actor JSON-LD</li>
 *   <li>{@code POST /ap/inbox} — accepts Follow / Create / Undo</li>
 *   <li>{@code GET  /ap/outbox} — returns empty OrderedCollection</li>
 * </ul>
 *
 * <pre>{@code
 * node.use(new ActivityPubPlugin(ActivityPubConfig.of(
 *         "example.com", "node",
 *         privateKeyPem, publicKeyPem)));
 * }</pre>
 */
public class ActivityPubPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(ActivityPubPlugin.class);

    private static final String ACTIVITY_STREAMS = "https://www.w3.org/ns/activitystreams";
    private static final String SECURITY_V1      = "https://w3id.org/security/v1";
    private static final String ACTIVITY_JSON    = "application/activity+json";

    private final ActivityPubConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> followers = Collections.synchronizedSet(new LinkedHashSet<>());
    private PrivateKey privateKey;
    private HttpClient httpClient;
    private VNamedQueue outboxQueue;

    public ActivityPubPlugin(ActivityPubConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.activitypub"; }
    @Override public String getName()    { return "VATN ActivityPub Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.15-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        privateKey = config.loadPrivateKey();
        httpClient = HttpClient.newHttpClient();

        // Use VQueueService for reliable outbox delivery with retry, if available
        Optional<VQueueService> queueSvcOpt = ctx.getService(VQueueService.class);
        if (queueSvcOpt.isPresent()) {
            outboxQueue = queueSvcOpt.get().queue("ap.outbox",
                VClaimOptions.defaults().withMaxAttempts(5).withBackoff(Duration.ofSeconds(30)));
            outboxQueue.consume("ap.outbox.worker", job -> {
                JsonNode envelope = mapper.readTree(job.payload());
                doSendActivity(envelope.get("inbox").asText(), envelope.get("body").asText());
            });
            log.info("ActivityPub outbox queue enabled — deliveries will be retried up to 5 times");
        }

        ActivityPubService service = new ActivityPubService() {
            @Override public String getActorUrl()     { return config.getActorUrl(); }
            @Override public String getPublicKeyPem() { return config.getPublicKeyPem(); }

            @Override
            public void sendActivity(String targetInbox, String activityJson) {
                doSendActivity(targetInbox, activityJson);
            }
        };
        ctx.registerService(ActivityPubService.class, service);

        // ── .well-known/webfinger ─────────────────────────────────────────────
        ctx.register("/.well-known", routes -> routes.get("/webfinger", (req, res) -> {
            String resource = req.getQueryParam("resource");
            String expected = "acct:" + config.getUsername() + "@" + config.getDomain();
            if (!expected.equals(resource)) {
                res.status(404).sendJson("{\"error\":\"not found\"}");
                return;
            }
            String json = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("subject", expected)
                    .set("links", mapper.createArrayNode().add(
                            mapper.createObjectNode()
                                    .put("rel", "self")
                                    .put("type", ACTIVITY_JSON)
                                    .put("href", config.getActorUrl()))));
            res.header("Content-Type", "application/jrd+json").send(json);
        }));

        // ── /ap routes ────────────────────────────────────────────────────────
        ctx.register("/ap", routes -> routes

            .get("/actor", (req, res) -> {
                var actor = mapper.createObjectNode();
                actor.putArray("@context").add(ACTIVITY_STREAMS).add(SECURITY_V1);
                actor.put("id",               config.getActorUrl())
                     .put("type",             "Service")
                     .put("preferredUsername", config.getUsername())
                     .put("name",              config.getUsername() + "@" + config.getDomain())
                     .put("inbox",             config.getInboxUrl())
                     .put("outbox",            config.getOutboxUrl());

                var key = actor.putObject("publicKey");
                key.put("id",           config.getKeyId())
                   .put("owner",        config.getActorUrl())
                   .put("publicKeyPem", config.getPublicKeyPem());

                res.header("Content-Type", ACTIVITY_JSON).send(mapper.writeValueAsString(actor));
            })

            .post("/inbox", (req, res) -> {
                String body = req.getBody();
                if (body == null || body.isBlank()) {
                    res.status(400).sendJson("{\"error\":\"empty body\"}");
                    return;
                }
                JsonNode activity;
                try { activity = mapper.readTree(body); }
                catch (Exception e) { res.status(400).sendJson("{\"error\":\"invalid JSON\"}"); return; }

                String type     = activity.path("type").asText();
                String actorUri = activity.path("actor").asText();

                switch (type) {
                    case "Follow" -> {
                        followers.add(actorUri);
                        log.info("New follower: {}", actorUri);
                        sendAccept(activity, actorUri, service);
                    }
                    case "Undo" -> {
                        String innerType = activity.path("object").path("type").asText();
                        if ("Follow".equals(innerType)) {
                            followers.remove(actorUri);
                            log.info("Unfollowed by: {}", actorUri);
                        }
                    }
                    default -> log.debug("Inbox received type={} from {}", type, actorUri);
                }
                res.status(202).sendJson("{}");
            })

            .get("/outbox", (req, res) -> {
                var collection = mapper.createObjectNode();
                collection.put("@context",   ACTIVITY_STREAMS)
                          .put("id",         config.getOutboxUrl())
                          .put("type",       "OrderedCollection")
                          .put("totalItems", 0);
                collection.putArray("orderedItems");
                res.header("Content-Type", ACTIVITY_JSON).send(mapper.writeValueAsString(collection));
            })
        );
    }

    private void doSendActivity(String targetInbox, String activityJson) {
        try {
            var req = HttpSignatureUtil.buildSignedPost(
                    URI.create(targetInbox), activityJson, config.getKeyId(), privateKey);
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("HTTP " + resp.statusCode() + " from " + targetInbox);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deliver activity to " + targetInbox, e);
        }
    }

    private void sendAccept(JsonNode originalActivity, String targetActorUrl, ActivityPubService service) {
        Thread.ofVirtual().start(() -> {
            try {
                String inboxUrl = resolveInbox(targetActorUrl);
                if (inboxUrl == null) return;

                var accept = mapper.createObjectNode();
                accept.put("@context", ACTIVITY_STREAMS)
                      .put("type",     "Accept")
                      .put("actor",    config.getActorUrl())
                      .set("object",   originalActivity);
                String body = mapper.writeValueAsString(accept);

                if (outboxQueue != null) {
                    String envelope = mapper.writeValueAsString(
                        mapper.createObjectNode().put("inbox", inboxUrl).put("body", body));
                    outboxQueue.enqueue(envelope);
                } else {
                    service.sendActivity(inboxUrl, body);
                }
            } catch (Exception e) {
                log.warn("Failed to send Accept to {}: {}", targetActorUrl, e.getMessage());
            }
        });
    }

    private String resolveInbox(String actorUrl) {
        try {
            var req = java.net.http.HttpRequest.newBuilder(URI.create(actorUrl))
                    .GET()
                    .header("Accept", ACTIVITY_JSON)
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode actor = mapper.readTree(resp.body());
                return actor.path("inbox").asText(null);
            }
        } catch (Exception e) {
            log.warn("Could not resolve inbox for {}: {}", actorUrl, e.getMessage());
        }
        return null;
    }
}
