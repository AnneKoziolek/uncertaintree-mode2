package uncertaintree.mode2;

import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntNum;
import com.microsoft.z3.Model;
import com.microsoft.z3.RatNum;
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
        // One incremental solver; each query is push / add / check / pop. A new Solver per
        // query on the shared context made queries slower over time (native objects pile up).
        Solver solver = ctx.mkSolver();
        long queries = 0;
        long nanos = 0;
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("MODE2-SOLVER READY " + port);
            System.out.flush();
            while (true) {
                try (Socket s = ss.accept();
                        ObjectInputStream in = new ObjectInputStream(s.getInputStream());
                        ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
                    Object req = in.readObject();
                    if ("SHUTDOWN".equals(req)) {
                        out.writeObject("BYE");
                        return;
                    }
                    String status;
                    HashMap<String, String> model = null;
                    try {
                        Z3JavaTranslator t = new Z3JavaTranslator(ctx);
                        ((Expression) req).accept(t);
                        long q0 = System.nanoTime();
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
                        solver.pop();
                        nanos += System.nanoTime() - q0;
                        if (++queries % 25 == 0) {
                            System.out.printf("server: %d queries, %.1f ms per query in Z3%n", queries, nanos / 1e6 / queries);
                        }
                    } catch (Throwable e) {
                        status = "ERROR " + e;
                    }
                    out.writeObject(status);
                    out.writeObject(model);
                    out.flush();
                } catch (Exception connectionError) {
                    // one broken client connection must not stop the service
                    System.out.println("connection failed: " + connectionError);
                }
            }
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
