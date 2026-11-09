package uncertaintree.mode2;

import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.Operation;
import za.ac.sun.cs.green.expr.Variable;

/** Depth-limited printing of Green expressions (their recursive toString overflows on deep trees). */
public final class Exprs {
    private Exprs() {}

    public static int depth(Expression e) {
        int best = 0;
        java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
        stack.push(new Object[] {e, 1});
        while (!stack.isEmpty()) {
            Object[] top = stack.pop();
            Expression x = (Expression) top[0];
            int d = (Integer) top[1];
            best = Math.max(best, d);
            if (x instanceof Operation) {
                Operation o = (Operation) x;
                for (int i = 0; i < o.getArity(); i++) {
                    stack.push(new Object[] {o.getOperand(i), d + 1});
                }
            }
        }
        return best;
    }

    public static String brief(Expression e, int maxDepth) {
        if (!(e instanceof Operation)) {
            return String.valueOf(e);
        }
        if (maxDepth <= 0) {
            return "…";
        }
        Operation o = (Operation) e;
        StringBuilder sb = new StringBuilder(o.getOperator().toString()).append('(');
        for (int i = 0; i < o.getArity(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(brief(o.getOperand(i), maxDepth - 1));
        }
        return sb.append(')').toString();
    }

    /** Names of the variables in e (iterative: deep trees overflow a recursive visitor). */
    public static java.util.Set<String> variables(Expression e) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        java.util.ArrayDeque<Expression> stack = new java.util.ArrayDeque<>();
        if (e != null) {
            stack.push(e);
        }
        while (!stack.isEmpty()) {
            Expression x = stack.pop();
            if (x instanceof Variable) {
                names.add(((Variable) x).getName());
            } else if (x instanceof Operation) {
                Operation o = (Operation) x;
                for (int i = 0; i < o.getArity(); i++) {
                    stack.push(o.getOperand(i));
                }
            }
        }
        return names;
    }

    private static final Expression TRUE = new za.ac.sun.cs.green.expr.BinaryOperation(
            Operation.Operator.EQ, new za.ac.sun.cs.green.expr.IntConstant(0), new za.ac.sun.cs.green.expr.IntConstant(0));

    /**
     * Topological closure of a path condition for bound computation: negations pushed into the
     * comparisons, strict comparisons made non-strict, disequalities dropped. Z3 4.8.14's Optimize
     * reports wrong suprema under strict real inequalities (maximize x s.t. 165 + x < 164 gave -2,
     * found 2026-11-09), but is correct on the closure; the caller verifies the result and decides
     * open or closed ends on the original condition.
     */
    public static Expression closure(Expression e) {
        return closure(e, false);
    }

    private static Expression closure(Expression e, boolean negated) {
        if (!(e instanceof Operation)) {
            return e;
        }
        Operation o = (Operation) e;
        Operation.Operator op = o.getOperator();
        switch (op) {
            case NOT:
                return closure(o.getOperand(0), !negated);
            case AND:
            case OR: {
                boolean and = (op == Operation.Operator.AND) != negated; // De Morgan under negation
                return new za.ac.sun.cs.green.expr.BinaryOperation(and ? Operation.Operator.AND : Operation.Operator.OR,
                        closure(o.getOperand(0), negated), closure(o.getOperand(1), negated));
            }
            case EQ: case NE: case LT: case LE: case GT: case GE: {
                Operation.Operator r = negated ? negate(op) : op;
                switch (r) {
                    case LT: r = Operation.Operator.LE; break;
                    case GT: r = Operation.Operator.GE; break;
                    case NE: return TRUE;
                    default: break;
                }
                return new za.ac.sun.cs.green.expr.BinaryOperation(r, o.getOperand(0), o.getOperand(1));
            }
            default:
                return negated ? new za.ac.sun.cs.green.expr.UnaryOperation(Operation.Operator.NOT, e) : e;
        }
    }

    private static Operation.Operator negate(Operation.Operator op) {
        switch (op) {
            case EQ: return Operation.Operator.NE;
            case NE: return Operation.Operator.EQ;
            case LT: return Operation.Operator.GE;
            case LE: return Operation.Operator.GT;
            case GT: return Operation.Operator.LE;
            case GE: return Operation.Operator.LT;
            default: throw new IllegalArgumentException(op.toString());
        }
    }

    /** True if e contains a product or division of two terms that both contain variables. */
    public static boolean nonlinear(Expression e) {
        java.util.ArrayDeque<Expression> stack = new java.util.ArrayDeque<>();
        stack.push(e);
        while (!stack.isEmpty()) {
            Expression x = stack.pop();
            if (x instanceof Operation) {
                Operation o = (Operation) x;
                Operation.Operator op = o.getOperator();
                if ((op == Operation.Operator.MUL || op == Operation.Operator.DIV) && o.getArity() == 2
                        && !variables(o.getOperand(0)).isEmpty() && !variables(o.getOperand(1)).isEmpty()) {
                    return true;
                }
                for (int i = 0; i < o.getArity(); i++) {
                    stack.push(o.getOperand(i));
                }
            }
        }
        return false;
    }
}
