package uncertaintree.mode2;

import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntNum;
import com.microsoft.z3.Model;
import com.microsoft.z3.RatNum;
import com.microsoft.z3.Optimize;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.Variable;
import za.ac.sun.cs.green.service.z3.Z3JavaTranslator;

/**
 * Plain-JVM satisfiability service for Mode 2 (like the earlier GreenServer, and like
 * knarr-server but stateless): one request per connection, a serialized Green
 * {@link Expression} in, {@code "SAT"}/{@code "UNSAT"}/{@code "UNKNOWN"} plus a model
 * (variable name to decimal value) out. Z3 runs here, not in the Galette-instrumented
 * JVM, where Bell reports SIGSEGVs at Z3 initialization.
 */
public final class Mode2SolverServer {
    private Mode2SolverServer() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        // One Z3 context for the lifetime of the process (a new context per query cost
        // 0.5-0.7 s each); a fresh Solver per query keeps queries independent.
        Context ctx = new Context();
        long queries = 0;
        long nanos = 0;
        long prepNanos = 0; // translating requests to Z3 (Green visitor)
        long replyNanos = 0; // writing replies
        long windowQueries = 0;
        long windowNanos = 0;
        long windowPrep = 0;
        long windowReply = 0;
        // Diagnosis: with -Dmode2.dumpSlowQueries=<dir>, every query slower than 200 ms is
        // written there as SMT-LIB (for offline experiments with encodings and tactics).
        String dumpDir = System.getProperty("mode2.dumpSlowQueries");
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("MODE2-SOLVER READY " + port);
            System.out.flush();
            while (true) {
                try (Socket s = accept(ss);
                        ObjectInputStream in = new ObjectInputStream(new java.io.BufferedInputStream(s.getInputStream()));
                        ObjectOutputStream out = new ObjectOutputStream(new java.io.BufferedOutputStream(s.getOutputStream()))) {
                    out.flush(); // stream header
                    // One incremental solver per connection (= per analysis); each query is
                    // push / add / check / pop. A new Solver per query on the shared context made
                    // queries slower over time (native objects pile up); one Solver for the whole
                    // process made Z3 5-9 times slower on the same queries in later analyses.
                    Solver solver = ctx.mkSolver();
                    // A check without limit can run for hours on a nonlinear real query (two
                    // design variables, (r - m)^2, demonstrator 2026-11-11): UNKNOWN after the limit.
                    com.microsoft.z3.Params checkLimit = ctx.mkParams();
                    checkLimit.add("timeout", Integer.getInteger("mode2.checkTimeoutMs", 30000));
                    solver.setParameters(checkLimit);
                  // One connection per analysis, many requests on it (a new socket and new object
                  // streams per query cost about 175 ms in the instrumented client).
                  while (true) {
                    Object req;
                    try {
                        req = in.readObject();
                    } catch (java.io.EOFException closed) {
                        break;
                    }
                    if ("SHUTDOWN".equals(req)) {
                        // Exit at once: the client does not read a reply (writing one failed with a
                        // broken pipe, so exit was never reached), and Z3's native threads would
                        // keep the JVM alive. Measured before: 10 s shutdown timeout per analysis.
                        System.exit(0);
                    }
                    String status;
                    HashMap<String, String> model = null;
                    if (req instanceof Object[] && "BOUNDS".equals(((Object[]) req)[0])) {
                        // {"BOUNDS", constraint, variable name}: least and greatest value of the
                        // variable under the constraint, by Z3's optimizer (two checks instead of
                        // a binary search of about 2 log2(width) checks over the socket).
                        Object[] r = (Object[]) req;
                        try {
                            long q0 = System.nanoTime();
                            model = bounds(ctx, (Expression) r[1], (String) r[2]);
                            status = model == null ? "UNSATISFIABLE" : "SATISFIABLE";
                            nanos += System.nanoTime() - q0;
                            queries++;
                        } catch (Throwable e) {
                            status = "ERROR " + e;
                        }
                        out.writeObject(status);
                        out.writeObject(model);
                        out.flush();
                        out.reset();
                        continue;
                    }
                    // {"CHECK", constraint, timeout ms}: a check with its own time limit (bisection).
                    Integer limitMs = null;
                    if (req instanceof Object[] && "CHECK".equals(((Object[]) req)[0])) {
                        limitMs = (Integer) ((Object[]) req)[2];
                        req = ((Object[]) req)[1];
                    }
                    if (limitMs != null) {
                        com.microsoft.z3.Params p = ctx.mkParams();
                        p.add("timeout", limitMs);
                        solver.setParameters(p);
                    }
                    try {
                        long r0 = System.nanoTime();
                        Z3JavaTranslator t = new Z3JavaTranslator(ctx);
                        ((Expression) req).accept(t);
                        long q0 = System.nanoTime();
                        prepNanos += q0 - r0;
                        solver.push();
                        solver.add((BoolExpr) t.getTranslation());
                        Status st = solver.check();
                        status = st.toString();
                        if (st == Status.SATISFIABLE) {
                            model = new HashMap<>();
                            Model m = solver.getModel();
                            for (Map.Entry<Variable, Expr> v : t.getVariableMap().entrySet()) {
                                model.put(v.getKey().toString(), decimal(m.eval(v.getValue(), true)));
                            }
                        }
                        long q = System.nanoTime() - q0;
                        if (dumpDir != null && !dumpDir.isBlank() && q > 200_000_000L) {
                            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dumpDir));
                            java.nio.file.Files.writeString(java.nio.file.Path.of(dumpDir,
                                    String.format("q%04d-%dms-%s.smt2", queries, q / 1_000_000, st)),
                                    solver.toString() + "\n(check-sat)\n");
                        }
                        solver.pop();
                        nanos += q;
                        ++queries;
                    } catch (Throwable e) {
                        status = "ERROR " + e;
                    }
                    if (limitMs != null) {
                        solver.setParameters(checkLimit); // back to the connection's default limit
                    }
                    long w0 = System.nanoTime();
                    out.writeObject(status);
                    out.writeObject(model);
                    out.flush();
                    out.reset();
                    replyNanos += System.nanoTime() - w0;
                    if (queries - windowQueries >= 25) {
                        long n = queries - windowQueries;
                        System.out.printf("server: queries %d-%d: per query %.1f ms Z3, %.1f ms translation, %.1f ms reply%n",
                                windowQueries + 1, queries, (nanos - windowNanos) / 1e6 / n,
                                (prepNanos - windowPrep) / 1e6 / n, (replyNanos - windowReply) / 1e6 / n);
                        windowQueries = queries;
                        windowNanos = nanos;
                        windowPrep = prepNanos;
                        windowReply = replyNanos;
                    }
                  }
                } catch (Exception connectionError) {
                    // one broken client connection must not stop the service
                    System.out.println("connection failed: " + connectionError);
                }
            }
        }
    }

    /** Map with "min" and "max" of {@code var} under {@code e}, or null if {@code e} is unsatisfiable. */
    private static HashMap<String, String> bounds(Context ctx, Expression e, String var) throws Exception {
        Z3JavaTranslator t = new Z3JavaTranslator(ctx);
        e.accept(t);
        BoolExpr constraint = (BoolExpr) t.getTranslation(); // pops the translator's stack: once only
        Expr x = null;
        for (Map.Entry<Variable, Expr> v : t.getVariableMap().entrySet()) {
            if (v.getKey().toString().equals(var)) {
                x = v.getValue();
            }
        }
        if (x == null) {
            throw new IllegalArgumentException("variable " + var + " not in the constraint");
        }
        HashMap<String, String> result = new HashMap<>();
        for (boolean min : new boolean[] {true, false}) {
            Optimize opt = ctx.mkOptimize();
            com.microsoft.z3.Params limit = ctx.mkParams();
            limit.add("timeout", Integer.getInteger("mode2.optimizeTimeoutMs", 10000)); // then the engine bisects
            opt.setParameters(limit);
            opt.Add(constraint);
            Optimize.Handle<?> h = min ? opt.MkMinimize(x) : opt.MkMaximize(x);
            Status st = opt.Check();
            if (st == Status.UNSATISFIABLE) {
                return null;
            }
            if (st != Status.SATISFIABLE) {
                throw new IllegalStateException("optimizer: " + st);
            }
            Expr value = h.getValue();
            String text = decimal(value);
            if (!(value instanceof IntNum) && !(value instanceof RatNum) && !(value instanceof BitVecNum)) {
                // Strict bound of a real variable: Z3 answers "b + epsilon" or "b - epsilon"
                // (an infinitesimal); report b and an open end.
                text = Double.toString(evalEpsilonFree(value.toString()));
                result.put(min ? "minOpen" : "maxOpen", Boolean.toString(value.toString().contains("epsilon")));
            }
            result.put(min ? "min" : "max", text);
        }
        return result;
    }

    /** Accepted socket without Nagle: replies are small and written in pieces (40 ms per query otherwise). */
    private static Socket accept(ServerSocket ss) throws java.io.IOException {
        Socket s = ss.accept();
        s.setTcpNoDelay(true);
        return s;
    }

    /**
     * Value of an SMT-LIB arithmetic term with the infinitesimal {@code epsilon} set to 0, e.g.
     * "(+ (- 5.0) epsilon)" or "(+ 5.0 (* (- 1.0) epsilon))"; also plain numerals and "a/b".
     */
    static double evalEpsilonFree(String term) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(|\\)|[^\\s()]+").matcher(term);
        while (m.find()) {
            tokens.add(m.group());
        }
        int[] pos = {0};
        return eval(tokens, pos);
    }

    private static double eval(java.util.List<String> t, int[] pos) {
        String tok = t.get(pos[0]++);
        if (!"(".equals(tok)) {
            if ("epsilon".equals(tok)) {
                return 0.0;
            }
            if (tok.contains("/")) {
                String[] q = tok.split("/");
                return Double.parseDouble(q[0]) / Double.parseDouble(q[1]);
            }
            return Double.parseDouble(tok.replace("?", ""));
        }
        String op = t.get(pos[0]++);
        java.util.List<Double> args = new java.util.ArrayList<>();
        while (!")".equals(t.get(pos[0]))) {
            args.add(eval(t, pos));
        }
        pos[0]++;
        switch (op) {
            case "+": return args.stream().mapToDouble(Double::doubleValue).sum();
            case "-": return args.size() == 1 ? -args.get(0) : args.get(0) - args.stream().skip(1).mapToDouble(Double::doubleValue).sum();
            case "*": return args.stream().mapToDouble(Double::doubleValue).reduce(1, (a, b) -> a * b);
            case "/": return args.get(0) / args.get(1);
            case "to_real": return args.get(0);
            default: throw new IllegalArgumentException("unsupported term " + op + " in " + t);
        }
    }

    private static String decimal(Expr e) {
        if (e instanceof BitVecNum) {
            BitVecNum b = (BitVecNum) e;
            java.math.BigInteger v = b.getBigInteger();
            int size = b.getSortSize();
            if (v.testBit(size - 1)) {
                v = v.subtract(java.math.BigInteger.ONE.shiftLeft(size));
            }
            return v.toString();
        }
        if (e instanceof IntNum) {
            return ((IntNum) e).getBigInteger().toString();
        }
        if (e instanceof RatNum) {
            return ((RatNum) e).toDecimalString(10);
        }
        return e.toString();
    }
}
