package uncertaintree.mode2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import tools.vitruv.change.propagation.ChangePropagationSpecification;

/**
 * Warm Mode-2 worker: a long-running HTTP service in the Galette-instrumented JVM.
 *
 * <p>The live system (e.g. a demonstrator) stays a plain JVM and hands over a VSUM snapshot;
 * the worker analyses it with {@link Mode2Engine} and returns a {@link Mode2Result} as JSON.
 * Uses the JDK's built-in HTTP server (no extra dependency). One analysis at a time.
 *
 * <pre>
 * GET  /health   -&gt; {"ready": true, "analyses": n}
 * POST /analyze  body: Request (JSON) -&gt; Mode2Result (JSON)
 * </pre>
 *
 * <p>Start: {@code Mode2Worker <port> [warm-up request file]}; it must run with the Galette
 * agent and {@code -Dgalette.symbolic.branchPrefixes=...} like the integration tests.
 */
public final class Mode2Worker {

    /** Mode 1's inputs, by name, so that a plain-JVM client can send them as JSON. */
    public static final class Request {
        public String scenario = "request";
        /** Nominal VSUM folder (snapshot), prepared like Mode 1's (models.models, uuid, correspondences). */
        public String vsumState;
        public String oclFile;
        /** Fully qualified class names of the change propagation specifications. */
        public List<String> cps = new ArrayList<>();
        /** Fully qualified class names of the view root types, including UncertaintyAnnotationRepository. */
        public List<String> rootTypes = new ArrayList<>();
        /** Fully qualified class names of the generated EPackage interfaces to register (eNS_URI, eINSTANCE). */
        public List<String> packages = new ArrayList<>();
        public int solverPort = 9420;
        public int maxPaths = 40;
        /** Changes coming in on the uncertain model, applied after the realization (optional). */
        public List<Mode2Engine.AttributeChange> incomingChanges = new ArrayList<>();
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    private int analyses;

    public Mode2Worker(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/health", ex -> send(ex, 200, "{\"ready\": true, \"analyses\": " + analyses + "}"));
        server.createContext("/analyze", this::analyze);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        stopSolver();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void analyze(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\": \"POST only\"}");
            return;
        }
        try (InputStream in = ex.getRequestBody()) {
            Request req = JSON.readValue(in, Request.class);
            Mode2Result r = run(req);
            send(ex, 200, JSON.writeValueAsString(r));
        } catch (Exception e) {
            send(ex, 500, JSON.writeValueAsString(java.util.Map.of("error", String.valueOf(e))));
        }
    }

    /** Solver process for the worker's lifetime (warm JIT for translation and Z3's Java API). */
    private Mode2Solver solver;
    private int solverPort;

    /** Stops the solver process (also from a shutdown hook: a child process outlives the JVM). */
    public synchronized void stopSolver() {
        if (solver != null) {
            try {
                solver.close();
            } catch (Exception ignored) {
            }
            solver = null;
        }
    }

    /** Runs one analysis for a request (also used for the warm-up). */
    public synchronized Mode2Result run(Request req) throws Exception {
        register(req.packages);
        Mode2Engine.Config c = new Mode2Engine.Config();
        c.scenario = req.scenario;
        c.vsumState = Path.of(req.vsumState).toAbsolutePath();
        c.oclFile = Path.of(req.oclFile).toAbsolutePath();
        c.cps = new ArrayList<>();
        for (String name : req.cps) {
            c.cps.add((ChangePropagationSpecification) Class.forName(name).getDeclaredConstructor().newInstance());
        }
        c.rootTypes = new ArrayList<>();
        for (String name : req.rootTypes) {
            c.rootTypes.add(Class.forName(name));
        }
        c.solverPort = req.solverPort;
        if (solver == null || solverPort != req.solverPort) {
            if (solver != null) {
                solver.close();
            }
            solver = new Mode2Solver(req.solverPort);
            solverPort = req.solverPort;
        }
        c.solver = solver;
        c.maxPaths = req.maxPaths;
        c.incomingChanges = new ArrayList<>(req.incomingChanges);
        try {
            Mode2Result r = new Mode2Engine(c).analyze();
            analyses++;
            return r;
        } finally {
            solver.endAnalysis(); // a fresh Z3 Solver for the next analysis (see endAnalysis)
        }
    }

    private static void register(List<String> packageInterfaces) throws ReflectiveOperationException {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().putIfAbsent("*", new XMIResourceFactoryImpl());
        for (String name : packageInterfaces) {
            Class<?> p = Class.forName(name);
            String nsUri = (String) p.getField("eNS_URI").get(null);
            EPackage instance = (EPackage) p.getField("eINSTANCE").get(null);
            EPackage.Registry.INSTANCE.put(nsUri, instance);
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void main(String[] args) throws Exception {
        Mode2Worker w = new Mode2Worker(Integer.parseInt(args[0]));
        Runtime.getRuntime().addShutdownHook(new Thread(w::stopSolver));
        if (args.length > 1) {
            long t = System.nanoTime();
            Request warm = JSON.readValue(Files.readString(Path.of(args[1])), Request.class);
            w.run(warm);
            System.out.printf("MODE2-WORKER warm-up done in %.1f s%n", (System.nanoTime() - t) / 1e9);
        }
        w.start();
        System.out.println("MODE2-WORKER READY " + w.port());
    }
}
