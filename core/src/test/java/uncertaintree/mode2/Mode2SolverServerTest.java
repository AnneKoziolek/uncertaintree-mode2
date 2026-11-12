package uncertaintree.mode2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.z3.Context;
import com.microsoft.z3.Solver;
import java.util.HashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import za.ac.sun.cs.green.expr.BinaryOperation;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.Operation.Operator;
import za.ac.sun.cs.green.expr.RealConstant;
import za.ac.sun.cs.green.expr.RealVariable;

/**
 * Z3 behaviour the solver process must respect (concept 21; probes in the stack's
 * docs/notes/2026-11-12-z3-probes): exact ends of non-dyadic bounds, algebraic model values,
 * non-finite constants. Plain JVM, Z3 via z3-turnkey.
 */
class Mode2SolverServerTest {

    static Context ctx;

    @BeforeAll
    static void open() {
        ctx = new Context();
    }

    @AfterAll
    static void close() {
        ctx.close();
    }

    static final RealVariable X = new RealVariable("x", null, 0.0, 1.0);

    static Expression op(Operator o, Expression a, Expression b) {
        return new BinaryOperation(o, a, b);
    }

    static Expression c(double v) {
        return new RealConstant(v);
    }

    /** 1 <= 3x <= 2: [1/3, 2/3], both ends attained; the decimals 0.3333333333 and 0.6666666666 lie inside. */
    @Test
    void nonDyadicBoundsAreClosedAndTight() throws Exception {
        Expression threeX = op(Operator.MUL, c(3.0), X);
        Expression pc = op(Operator.AND, op(Operator.GE, threeX, c(1.0)), op(Operator.LE, threeX, c(2.0)));
        HashMap<String, String> b = Mode2SolverServer.bounds(ctx, pc, "x", pc);
        assertEquals("0.3333333333?", b.get("min"));
        assertEquals("true", b.get("tight"));
        assertEquals("false", b.get("minOpen"), "1/3 is attained");
        assertEquals("false", b.get("maxOpen"), "2/3 is attained");
    }

    /** 1 < 3x <= 2: the closure has the same optimum; the lower end is not attained. */
    @Test
    void strictNonDyadicBoundIsOpen() throws Exception {
        Expression threeX = op(Operator.MUL, c(3.0), X);
        Expression pc = op(Operator.AND, op(Operator.GT, threeX, c(1.0)), op(Operator.LE, threeX, c(2.0)));
        Expression closure = op(Operator.AND, op(Operator.GE, threeX, c(1.0)), op(Operator.LE, threeX, c(2.0)));
        HashMap<String, String> b = Mode2SolverServer.bounds(ctx, closure, "x", pc);
        assertEquals("true", b.get("tight"));
        assertEquals("true", b.get("minOpen"));
        assertEquals("false", b.get("maxOpen"));
    }

    /** x * x = 2: nlsat answers with an algebraic number, reported as a truncated decimal. */
    @Test
    void algebraicModelValueIsADecimal() throws Exception {
        RealVariable y = new RealVariable("y", null, 0.0, 2.0);
        Solver s = ctx.mkSolver();
        // Green's variable bounds do not enter the formula (Mode 2 adds them with domain())
        Expression pc = op(Operator.AND, op(Operator.GE, y, c(0.0)), op(Operator.EQ, op(Operator.MUL, y, y), c(2.0)));
        Object[] a = Mode2SolverServer.check(ctx, s, pc, 10000);
        assertEquals("SATISFIABLE", a[0]);
        @SuppressWarnings("unchecked")
        String v = ((HashMap<String, String>) a[1]).get("y");
        assertTrue(v.startsWith("1.4142135623"), v);
        assertEquals(Math.sqrt(2), Double.parseDouble(v.replace("?", "")), 1e-9);
    }

    /** A NaN constant is an explicit ERROR, not a silently wrong formula. */
    @Test
    void nonFiniteConstantIsAnError() throws Exception {
        Solver s = ctx.mkSolver();
        Object[] a = Mode2SolverServer.check(ctx, s, op(Operator.LT, X, c(Double.NaN)), 10000);
        assertTrue(((String) a[0]).startsWith("ERROR non-finite constant"), (String) a[0]);
    }
}
