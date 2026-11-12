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
                            model = bounds(ctx, (Expression) r[1], (String) r[2], r.length > 3 ? (Expression) r[3] : null);
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
                        long q0 = System.nanoTime();
                        Object[] answer = check(ctx, solver, (Expression) req,
                                limitMs != null ? limitMs : Integer.getInteger("mode2.checkTimeoutMs", 30000));
                        status = (String) answer[0];
                        @SuppressWarnings("unchecked")
                        HashMap<String, String> m = (HashMap<String, String>) answer[1];
                        model = m;
                        long q = System.nanoTime() - q0;
                        if (dumpDir != null && !dumpDir.isBlank() && q > 200_000_000L) {
                            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dumpDir));
                            java.nio.file.Files.writeString(java.nio.file.Path.of(dumpDir,
                                    String.format("q%04d-%dms-%s.smt2", queries, q / 1_000_000, status.split(" ")[0])),
                                    req + "\n");
                        }
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
    /** One thread for Z3 calls, so the connection's thread can enforce a wall-clock limit. */
    private static final java.util.concurrent.ExecutorService Z3 = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "z3");
        t.setDaemon(true);
        return t;
    });

    /** Checks stopped by the wall-clock limit (Z3 did not honour its own timeout). */
    static int hardStops;
    /** Whether the last call of within ended by the wall-clock limit (one Z3 thread, so no race). */
    static volatile boolean lastHardStop;

    /**
     * Runs a Z3 check with a hard wall-clock limit: Z3's own timeout parameter is not honoured on
     * every nonlinear query (one bisection step ran over two hours with a 2 s limit, demonstrator
     * 2026-11-11). After the limit plus 5 s the context is interrupted and the answer is UNKNOWN.
     */
    static Status within(Context ctx, java.util.concurrent.Callable<Status> check, int limitMs) throws Exception {
        java.util.concurrent.Future<Status> f = Z3.submit(check);
        try {
            return f.get(limitMs + 5000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            ctx.interrupt();
            hardStops++;
            lastHardStop = true;
            System.out.println("[mode2-solver] server: check stopped after " + (limitMs + 5000) + " ms (" + hardStops + " so far)");
            try {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS); // the interrupted check returns UNKNOWN
            } catch (Exception ignored) {
                // still running or failed: the answer stays UNKNOWN
            }
            return Status.UNKNOWN;
        } catch (java.util.concurrent.ExecutionException failed) {
            throw failed.getCause() instanceof Exception ? (Exception) failed.getCause() : failed;
        }
    }

    /**
     * "min" and "max" of {@code var} under {@code e} (decimal, "?" if truncated), or null if
     * {@code e} is unsatisfiable. With {@code original} (the condition whose closure {@code e} is),
     * also decided here with Z3's exact rational optimum, never with a decimal: "tight" (no
     * solution of {@code original} below min or above max) and "minOpen"/"maxOpen" (the bound is
     * not attained in {@code original}), each "true", "false" or "unknown" (UNKNOWN or ERROR).
     * A decimal such as 0.3333333333 for 1/3 lies inside the interval, so checks against it
     * would report a closed end as open (concept 21).
     */
    static HashMap<String, String> bounds(Context ctx, Expression e, String var, Expression original) throws Exception {
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
        Expr[] optimum = new Expr[2];
        for (boolean min : new boolean[] {true, false}) {
            Optimize opt = ctx.mkOptimize();
            com.microsoft.z3.Params limit = ctx.mkParams();
            limit.add("timeout", Integer.getInteger("mode2.optimizeTimeoutMs", 10000)); // then the engine bisects
            opt.setParameters(limit);
            opt.Add(constraint);
            Optimize.Handle<?> h = min ? opt.MkMinimize(x) : opt.MkMaximize(x);
            Status st = within(ctx, opt::Check, Integer.getInteger("mode2.optimizeTimeoutMs", 10000));
            if (st == Status.UNSATISFIABLE) {
                return null;
            }
            if (st != Status.SATISFIABLE) {
                throw new IllegalStateException("optimizer: " + st);
            }
            Expr value = h.getValue();
            optimum[min ? 0 : 1] = value;
            String text = decimal(value);
            if (!(value instanceof IntNum) && !(value instanceof RatNum) && !(value instanceof BitVecNum)) {
                // Strict bound of a real variable: Z3 answers "b + epsilon" or "b - epsilon"
                // (an infinitesimal); report b and an open end.
                text = Double.toString(evalEpsilonFree(value.toString()));
                result.put(min ? "minOpen" : "maxOpen", Boolean.toString(value.toString().contains("epsilon")));
            }
            result.put(min ? "min" : "max", text);
        }
        if (original != null) {
            exactEnds(ctx, original, x, optimum, result);
        }
        return result;
    }

    /** Tightness and openness of the optimum under {@code original}, with exact numerals (see bounds). */
    private static void exactEnds(Context ctx, Expression original, Expr x, Expr[] optimum, HashMap<String, String> result)
            throws Exception {
        boolean numerals = optimum[0] instanceof RatNum || optimum[0] instanceof IntNum;
        numerals &= optimum[1] instanceof RatNum || optimum[1] instanceof IntNum;
        if (!numerals) {
            result.put("tight", "unknown");
            result.put("minOpen", "unknown");
            result.put("maxOpen", "unknown");
            return;
        }
        Z3JavaTranslator t = new Z3JavaTranslator(ctx);
        original.accept(t);
        BoolExpr pc = (BoolExpr) t.getTranslation();
        Solver s = ctx.mkSolver();
        int limit = Integer.getInteger("mode2.checkTimeoutMs", 30000);
        com.microsoft.z3.Params p = ctx.mkParams();
        p.add("timeout", limit);
        s.setParameters(p);
        s.add(pc);
        @SuppressWarnings("unchecked")
        com.microsoft.z3.ArithExpr<com.microsoft.z3.ArithSort> xa = (com.microsoft.z3.ArithExpr<com.microsoft.z3.ArithSort>) x;
        @SuppressWarnings("unchecked")
        com.microsoft.z3.ArithExpr<com.microsoft.z3.ArithSort> lo = (com.microsoft.z3.ArithExpr<com.microsoft.z3.ArithSort>) optimum[0];
        @SuppressWarnings("unchecked")
        com.microsoft.z3.ArithExpr<com.microsoft.z3.ArithSort> hi = (com.microsoft.z3.ArithExpr<com.microsoft.z3.ArithSort>) optimum[1];
        Status below = assuming(ctx, s, ctx.mkLt(xa, lo), limit);
        Status above = assuming(ctx, s, ctx.mkGt(xa, hi), limit);
        result.put("tight", below == Status.UNSATISFIABLE && above == Status.UNSATISFIABLE ? "true"
                : below == Status.SATISFIABLE || above == Status.SATISFIABLE ? "false" : "unknown");
        result.put("minOpen", openness(assuming(ctx, s, ctx.mkEq(xa, lo), limit)));
        result.put("maxOpen", openness(assuming(ctx, s, ctx.mkEq(xa, hi), limit)));
    }

    private static Status assuming(Context ctx, Solver s, BoolExpr extra, int limit) throws Exception {
        s.push();
        try {
            s.add(extra);
            return within(ctx, s::check, limit);
        } finally {
            s.pop();
        }
    }

    /** A bound is open iff it is not attained: UNSAT of "x = bound"; SAT closed; else unknown. */
    private static String openness(Status attained) {
        return attained == Status.UNSATISFIABLE ? "true" : attained == Status.SATISFIABLE ? "false" : "unknown";
    }

    /**
     * One check of {@code e} on the connection's incremental solver (push, add, check, pop):
     * {status, model}. Status "SATISFIABLE" (model: decimal value per variable, a truncated
     * decimal marked "?"), "UNSATISFIABLE", "UNKNOWN <reason>" (Z3's reason, or "wall-clock" for
     * the hard limit) or "ERROR <reason>" (e.g. a NaN or infinite constant, which Z3's numeral
     * parser rejects and the translator would drop silently, leaving a wrong formula).
     */
    static Object[] check(Context ctx, Solver solver, Expression e, int limitMs) throws Exception {
        String bad = nonFinite(e);
        if (bad != null) {
            return new Object[] {"ERROR non-finite constant " + bad, null};
        }
        Z3JavaTranslator t = new Z3JavaTranslator(ctx);
        e.accept(t);
        solver.push();
        try {
            solver.add((BoolExpr) t.getTranslation());
            lastHardStop = false;
            Status st = within(ctx, solver::check, limitMs);
            if (st == Status.UNKNOWN) {
                return new Object[] {"UNKNOWN " + (lastHardStop ? "wall-clock" : reason(solver)), null};
            }
            HashMap<String, String> model = null;
            if (st == Status.SATISFIABLE) {
                model = new HashMap<>();
                Model m = solver.getModel();
                for (Map.Entry<Variable, Expr> v : t.getVariableMap().entrySet()) {
                    model.put(v.getKey().toString(), decimal(m.eval(v.getValue(), true)));
                }
            }
            return new Object[] {st.toString(), model};
        } finally {
            solver.pop();
        }
    }

    private static String reason(Solver solver) {
        try {
            return solver.getReasonUnknown();
        } catch (Exception e) {
            return "unknown reason";
        }
    }

    /** The first NaN or infinite real constant in {@code e}, or null. */
    static String nonFinite(Expression e) {
        java.util.ArrayDeque<Expression> stack = new java.util.ArrayDeque<>();
        if (e != null) {
            stack.push(e);
        }
        while (!stack.isEmpty()) {
            Expression x = stack.pop();
            if (x instanceof za.ac.sun.cs.green.expr.RealConstant) {
                double v = ((za.ac.sun.cs.green.expr.RealConstant) x).getValue();
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    return Double.toString(v);
                }
            } else if (x instanceof za.ac.sun.cs.green.expr.Operation) {
                za.ac.sun.cs.green.expr.Operation o = (za.ac.sun.cs.green.expr.Operation) x;
                for (int i = 0; i < o.getArity(); i++) {
                    if (o.getOperand(i) != null) {
                        stack.push(o.getOperand(i));
                    }
                }
            }
        }
        return null;
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
        if (e instanceof com.microsoft.z3.AlgebraicNum) {
            // an irrational sample point of nlsat, e.g. (root-obj (+ (^ x 2) (- 2)) 2) = 1.4142135623?
            return ((com.microsoft.z3.AlgebraicNum) e).toDecimal(10);
        }
        return e.toString();
    }
}
