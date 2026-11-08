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
    public int queries;
    public long nanos;

    public Mode2Solver(int port) throws Exception {
        this.port = port;
        String plainJava = System.getProperty("mode2.plainJava") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(plainJava, "-cp", System.getProperty("java.class.path"),
                Mode2SolverServer.class.getName(), String.valueOf(port));
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

    @SuppressWarnings("unchecked")
    public Result check(Expression e) throws Exception {
        long t = System.nanoTime();
        // As in Knarr's Symbolicator: Galette's SerializationMasks append tag wrappers
        // to the stream, which a plain JVM cannot read; disable them for the round trip.
        boolean prev = setSerializationPropagation(false);
        try (Socket s = new Socket("127.0.0.1", port);
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
            out.writeObject(e);
            out.flush();
            ObjectInputStream in = new ObjectInputStream(s.getInputStream());
            String status = (String) in.readObject();
            Map<String, String> model = (Map<String, String>) in.readObject();
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

    @Override
    public void close() throws Exception {
        boolean prev = setSerializationPropagation(false);
        try (Socket s = new Socket("127.0.0.1", port);
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
            out.writeObject("SHUTDOWN");
            out.flush();
        } catch (Exception ignored) {
        } finally {
            setSerializationPropagation(prev);
        }
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        process.destroyForcibly();
    }
}
