package dev.vatn.plugins.terminalphone;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;

import java.util.Map;

class TerminalPhoneHttpService implements VHttpService {

    private final TerminalPhoneService service;
    private final ObjectMapper         mapper = new ObjectMapper();

    TerminalPhoneHttpService(TerminalPhoneService service) {
        this.service = service;
    }

    @Override
    public void routing(VHttpRoutes routes) {
        routes.get("/address",  this::handleAddress);
        routes.get("/qr",       this::handleQr);
        routes.get("/status",   this::handleStatus);
        routes.post("/call",    this::handleCall);
        routes.post("/hangup",  this::handleHangup);
        routes.post("/text",    this::handleText);
    }

    private void handleAddress(VHttpRequest req, VHttpResponse res) throws Exception {
        res.sendJson(mapper.writeValueAsString(Map.of("address", service.getLocalAddress())));
    }

    private void handleQr(VHttpRequest req, VHttpResponse res) throws Exception {
        res.header("Content-Type", "text/plain; charset=UTF-8");
        res.send(service.getQrCode());
    }

    private void handleStatus(VHttpRequest req, VHttpResponse res) throws Exception {
        res.sendJson(mapper.writeValueAsString(Map.of(
            "address",  service.getLocalAddress(),
            "sessions", service.activeSessions()
        )));
    }

    private void handleCall(VHttpRequest req, VHttpResponse res) throws Exception {
        Map<?, ?> body    = mapper.readValue(req.getBody(), Map.class);
        String    address = (String) body.get("address");
        if (address == null || address.isBlank()) {
            res.status(400).sendJson("{\"error\":\"'address' is required\"}");
            return;
        }
        service.call(address);
        res.sendJson("{\"status\":\"dialing\"}");
    }

    private void handleHangup(VHttpRequest req, VHttpResponse res) throws Exception {
        service.hangup();
        res.sendJson("{\"status\":\"hung_up\"}");
    }

    private void handleText(VHttpRequest req, VHttpResponse res) throws Exception {
        Map<?, ?> body = mapper.readValue(req.getBody(), Map.class);
        String    msg  = (String) body.get("message");
        if (msg == null || msg.isBlank()) {
            res.status(400).sendJson("{\"error\":\"'message' is required\"}");
            return;
        }
        service.sendText(msg);
        res.sendJson("{\"status\":\"sent\"}");
    }
}
