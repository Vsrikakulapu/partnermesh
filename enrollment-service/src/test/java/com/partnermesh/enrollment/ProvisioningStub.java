package com.partnermesh.enrollment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for provisioning-service that honours the same contract (idempotent on
 * enrollmentId) and can be scripted to fail.
 */
final class ProvisioningStub implements AutoCloseable {

    enum Mode { NORMAL, ALWAYS_UNAVAILABLE, BAD_REQUEST }

    record Call(String idempotencyKey, String enrollmentId) {
    }

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> provisions = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger dropResponseNext = new AtomicInteger();
    private volatile Mode mode = Mode.NORMAL;

    ProvisioningStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/provisions", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        provisions.clear();
        calls.clear();
        failNext.set(0);
        dropResponseNext.set(0);
        mode = Mode.NORMAL;
    }

    void failNext(int n) {
        failNext.set(n);
    }

    void dropResponseNext(int n) {
        dropResponseNext.set(n);
    }

    void mode(Mode mode) {
        this.mode = mode;
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    Map<String, String> provisions() {
        return Map.copyOf(provisions);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode body = json.readTree(exchange.getRequestBody());
            String enrollmentId = body.path("enrollmentId").asText();
            calls.add(new Call(exchange.getRequestHeaders().getFirst("Idempotency-Key"), enrollmentId));

            if (mode == Mode.BAD_REQUEST) {
                respond(exchange, 400, "{\"title\":\"unknown program\"}");
                return;
            }
            if (mode == Mode.ALWAYS_UNAVAILABLE || take(failNext)) {
                respond(exchange, 503, "{\"title\":\"unavailable\"}");
                return;
            }
            boolean created = provisions.putIfAbsent(enrollmentId, "prv-" + enrollmentId.substring(0, 8)) == null;
            if (take(dropResponseNext)) {
                respond(exchange, 503, "{\"title\":\"response lost after commit\"}");
                return;
            }
            respond(exchange, created ? 201 : 200,
                    "{\"provisionId\":\"" + provisions.get(enrollmentId) + "\",\"created\":" + created + "}");
        }
    }

    private static boolean take(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
