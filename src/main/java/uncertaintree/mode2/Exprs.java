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
}
