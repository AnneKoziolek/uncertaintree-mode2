package uncertaintree.mode2;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Map;
import za.ac.sun.cs.green.expr.Expression;

/** Client side of {@link Mode2SolverServer}; also starts and stops the server process. */
public final class Mode2Solver implements AutoCloseable {
    public static final class Result {
        public final String status;
        public final Map<String, String> model;

        Result(String status, Map<String, String> model) {
            this.status = status;
            this.model = model;
        }

        public boolean sat() {
            return "SATISFIABLE".equals(status);
        }

        @Override
        public String toString() {
            return status + (model == null ? "" : " " + model);
        }
    }

    private final Process process;
    private final int port;
    /** One connection for all queries of an analysis (a socket per query cost about 175 ms). */
    private Socket conn;
    private int dumped;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    public int queries;
    public long nanos;
    /** Part of {@link #nanos} spent serializing requests in this (instrumented) JVM. */
    public long writeNanos;
    /** Part of {@link #nanos} spent deserializing the reply's model map in this JVM. */
    public long replyNanos;

    public Mode2Solver(int port) throws Exception {
        this.port = port;
        String plainJava = System.getProperty("mode2.plainJava") + File.separator + "bin" + File.separator + "java";
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(plainJava));
        String dump = System.getProperty("mode2.dumpSlowQueries");
        if (dump != null && !dump.isBlank()) {
            cmd.add("-Dmode2.dumpSlowQueries=" + System.getProperty("mode2.dumpSlowQueries"));
        }
        cmd.add("-Dmode2.checkTimeoutMs=" + Integer.getInteger("mode2.checkTimeoutMs", 30000));
        cmd.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"),
                Mode2SolverServer.class.getName(), String.valueOf(port)));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        process = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("MODE2-SOLVER READY")) {
                break;
            }
            System.out.println("[mode2-solver] " + line);
        }
        if (line == null) {
            throw new IllegalStateException("solver process did not start");
        }
        Thread drain = new Thread(() -> {
            try {
                String l;
                while ((l = r.readLine()) != null) {
                    System.out.println("[mode2-solver] " + l);
                }
            } catch (Exception ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();
    }

    /** Least and greatest value of {@code var} under {@code e}: status SATISFIABLE with model keys "min" and "max". */
    public Result bounds(Expression e, String var) throws Exception {
        return send(new Object[] {"BOUNDS", e, var});
    }

    /** Checks that ended UNKNOWN (Z3 gave up or hit mode2.checkTimeoutMs); the analysis treats them as unsatisfiable. */
    public int unknowns;

    /** A check with its own time limit (e.g. bisection steps, which tolerate an UNKNOWN). */
    public Result check(Expression e, int timeoutMs) throws Exception {
        Result r = send(new Object[] {"CHECK", e, timeoutMs});
        if ("UNKNOWN".equals(r.status)) {
            unknowns++;
        }
        return r;
    }

    public Result check(Expression e) throws Exception {
        Result r = send(e);
        if ("UNKNOWN".equals(r.status)) {
            unknowns++;
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private Result send(Object e) throws Exception {
        long t = System.nanoTime();
        // As in Knarr's Symbolicator: Galette's SerializationMasks append tag wrappers
        // to the stream, which a plain JVM cannot read; disable them for the round trip.
        boolean prev = setSerializationPropagation(false);
        try {
            if (conn == null) {
                conn = new Socket("127.0.0.1", port);
                conn.setTcpNoDelay(true);
                out = new ObjectOutputStream(new java.io.BufferedOutputStream(conn.getOutputStream()));
                out.flush(); // stream header first: the server's ObjectInputStream waits for it
                in = new ObjectInputStream(new java.io.BufferedInputStream(conn.getInputStream()));
            }
            String dumpQueries = System.getProperty("mode2.dumpQueries");
            if (dumpQueries != null && !dumpQueries.isBlank()) {
                // Diagnosis: every request as a serialized object, for offline replay.
                java.nio.file.Path dir = java.nio.file.Path.of(dumpQueries);
                java.nio.file.Files.createDirectories(dir);
                try (ObjectOutputStream f = new ObjectOutputStream(java.nio.file.Files.newOutputStream(
                        dir.resolve(String.format("q%05d.ser", dumped++))))) {
                    f.writeObject(e);
                }
            }
            long w = System.nanoTime();
            out.writeObject(e);
            out.flush();
            out.reset(); // no back references across queries (the table would only grow)
            writeNanos += System.nanoTime() - w;
            String status = (String) in.readObject();
            long r = System.nanoTime();
            Map<String, String> model = (Map<String, String>) in.readObject();
            replyNanos += System.nanoTime() - r;
            return new Result(status, model);
        } finally {
            setSerializationPropagation(prev);
            queries++;
            nanos += System.nanoTime() - t;
        }
    }

    private static boolean setSerializationPropagation(boolean value) {
        try {
            Class<?> cfg = Class.forName("edu.neu.ccs.prl.galette.internal.transform.Configuration");
            java.lang.reflect.Field f = cfg.getDeclaredField("PROPAGATE_THROUGH_SERIALIZATION");
            f.setAccessible(true);
            boolean prev = f.getBoolean(null);
            f.setBoolean(null, value);
            return prev;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Ends the connection of one analysis; the next query opens a new one. The server keeps one
     * incremental Solver per connection, and one Solver over several analyses made Z3 5-9 times
     * slower on the same queries (2026-11-08: a worker that kept its connection took 6.7 s, then
     * 58, 29 and 19 s for the same request). The solver process and its Z3 context stay warm.
     */
    public void endAnalysis() {
        if (conn == null) {
            return;
        }
        boolean prev = setSerializationPropagation(false);
        try {
            conn.close();
        } catch (Exception ignored) {
        } finally {
            conn = null;
            out = null;
            in = null;
            setSerializationPropagation(prev);
        }
    }

    @Override
    public void close() throws Exception {
        long t = System.nanoTime();
        try {
            shutdown();
        } finally {
            System.out.printf("[mode2-solver] close took %d ms%n", (System.nanoTime() - t) / 1_000_000);
        }
    }

    private void shutdown() throws Exception {
        boolean prev = setSerializationPropagation(false);
        try {
            if (conn == null) {
                conn = new Socket("127.0.0.1", port);
                out = new ObjectOutputStream(conn.getOutputStream());
            }
            out.writeObject("SHUTDOWN");
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (Exception ignored) {
            }
            setSerializationPropagation(prev);
        }
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        process.destroyForcibly();
    }
}
