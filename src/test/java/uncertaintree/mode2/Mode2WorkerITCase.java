package uncertaintree.mode2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Warm worker over HTTP: scenario A sent twice (cold, then warm); checks the regions and
 * reports the latency a demonstrator would see.
 */
public class Mode2WorkerITCase {
    private static final ObjectMapper JSON = new ObjectMapper();

    @org.junit.jupiter.api.BeforeAll
    static void packages() {
        // for preparing the snapshot folder (Mode 1's setup); the worker registers its own
        Scenarios.registerPackages();
    }

    @Test
    void coldAndWarmRequest() throws Exception {
        Mode2Worker worker = new Mode2Worker(0);
        worker.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + worker.port();
            Assertions.assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/health")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            Mode2Worker.Request req = new Mode2Worker.Request();
            req.scenario = "A-worker";
            req.vsumState = Scenarios.prepare("A", "worker").toString();
            req.oclFile = Scenarios.oclFile().toString();
            req.cps = List.of("mir.reactions.cad2brakesystem.Cad2brakesystemChangePropagationSpecification",
                    "mir.reactions.brakesystem2cad.Brakesystem2cadChangePropagationSpecification");
            req.rootTypes = List.of("brakesystem.Brakesystem", "cad.CAD_Model", "uncertainty.UncertaintyAnnotationRepository");
            req.packages = List.of("cad.CadPackage", "brakesystem.BrakesystemPackage",
                    "tools.vitruv.dsls.reactions.runtime.correspondence.CorrespondencePackage",
                    "uncertainty.UncertaintyPackage", "simulink.SimuLinkPackage");
            req.solverPort = 9431;
            String body = JSON.writeValueAsString(req);

            for (String round : List.of("cold", "warm", "warm2", "warm3")) {
                long t = System.nanoTime();
                HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base + "/analyze"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                double s = (System.nanoTime() - t) / 1e9;
                Assertions.assertEquals(200, resp.statusCode(), resp.body());
                Mode2Result r = JSON.readValue(resp.body(), Mode2Result.class);
                System.out.printf("[mode2-worker] %s request: %.1f s, %d regions, %d executions, solver %d ms%n", round, s,
                        r.regions.size(), r.stats.concreteExecutions, r.stats.solverMillis);
                System.out.println("[mode2-worker]   phases ms: " + r.stats.phaseMillis + ", solver " + r.stats.solverMillis);
                Assertions.assertEquals(3, r.regions.size());
                Assertions.assertTrue(r.regions.stream().anyMatch(g -> g.consistent
                        && g.intervals.values().iterator().next()[0] == 325
                        && g.intervals.values().iterator().next()[1] == 329));
                Assertions.assertTrue(r.nominal.fixpointConfirmed);
            }
        } finally {
            worker.stop();
        }
    }
}
