package uncertaintree.mode2;

import brakesystem.BrakeDisk;
import brakesystem.Brakesystem;
import edu.gmu.swe.knarr.runtime.PathConstraintListener;
import edu.gmu.swe.knarr.runtime.PathUtils;
import edu.gmu.swe.knarr.runtime.Symbolicator;
import edu.neu.ccs.prl.galette.internal.runtime.symbolic.SymbolicListener;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.emf.ecore.EPackage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.multimodelocl.evaluator.Value;
import tools.vitruv.multimodelocl.pipeline.OCLCompiler;
import uncertaintree.utils.VsumUtils;
import uncertaintree.vsum.IdAttributeHandleStrategy;
import uncertaintree.vsum.execution.CopyingVitruvViewProvider;
import uncertaintree.vsum.execution.IdBasedVsumObjectResolver;
import uncertaintree.vsum.execution.VitruvTestViewDefinition;
import uncertaintree.vsum.execution.VsumViewSession;
import za.ac.sun.cs.green.expr.BVConstant;
import za.ac.sun.cs.green.expr.BVVariable;
import za.ac.sun.cs.green.expr.BinaryOperation;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.Operation.Operator;
import za.ac.sun.cs.green.expr.UnaryOperation;

/**
 * Mode 2, first end-to-end slice, on UncertainTree's CPS1.
 *
 * <p>Uncertainty {@code BrakeDiscDiameterUncertainty}: ParameterizedDiscreteRange 5/7 around
 * the nominal 330, i.e. the domain [325, 337] (Mode 1 enumerates these 13 states; consistent
 * exactly for d <= 329). Here the diameter is one symbolic variable with that domain. Each
 * path: check out, set the symbolic diameter, commit (real .reactions CPRs), evaluate the
 * three OCL invariants on the live model (MultiModelOCL, unchanged). Branches of the CPRs and
 * of the evaluator form the path condition. DART-style exploration: flip each branch of each
 * new path (prefix AND NOT b_i AND domain); Z3 (separate plain JVM) supplies the next input.
 * Result extents: per path, the solver determines [lo, hi] by binary search and then checks
 * that the path condition is exactly that interval (two UNSAT queries); otherwise the path
 * condition stays a predicate extent.
 */
public class Cps1Mode2ExplorationITCase {
    static final int NOMINAL = 330;
    static final int LO = 325;
    static final int HI = 337;
    static final BVVariable D = new BVVariable("diameterInMM", 32);
    static final int MAX_PATHS = 20;

    static final class PathResult {
        final int input;
        final List<Expression> branches = new ArrayList<>();
        final Map<String, Boolean> verdicts = new LinkedHashMap<>();
        long millis;
        String extent;

        PathResult(int input) {
            this.input = input;
        }

        boolean consistent() {
            return verdicts.values().stream().allMatch(Boolean::booleanValue);
        }
    }

    @BeforeAll
    static void registerPackages() {
        Cps1SymbolicDiameterITCase.registerPackages();
    }

    static Expression and(Expression a, Expression b) {
        return a == null ? b : (b == null ? a : new BinaryOperation(Operator.AND, a, b));
    }

    static Expression domain() {
        return and(new BinaryOperation(Operator.GE, D, new BVConstant(LO, 32)),
                new BinaryOperation(Operator.LE, D, new BVConstant(HI, 32)));
    }

    static Expression conj(List<Expression> es, int upTo) {
        Expression acc = null;
        for (int i = 0; i < upTo; i++) {
            acc = and(acc, es.get(i));
        }
        return acc;
    }

    static boolean verdict(Value v) {
        Object o = v == null ? null : v.getValue();
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof List) {
            for (Object e : (List<?>) o) {
                if (!Boolean.TRUE.equals(e) && !"true".equals(String.valueOf(e))) {
                    return false;
                }
            }
            return true;
        }
        return "true".equals(String.valueOf(o)) || "[true]".equals(String.valueOf(o));
    }

    @Test
    void exploreDiameterSymbolically() throws Exception {
        Path vsumState = Cps1SymbolicDiameterITCase.prepareVsumState("cps1-vsum-mode2");
        VsumUtils util = new VsumUtils();
        VirtualModel baseline = util.createDefaultVirtualModel(vsumState, Cps1SymbolicDiameterITCase.CPS);
        View bv = util.getDefaultView(baseline, Cps1SymbolicDiameterITCase.ROOT_TYPES);
        Brakesystem bs = bv.getRootObjects(Brakesystem.class).iterator().next();
        BrakeDisk disk = bs.getBrakeComponents().stream()
                .filter(BrakeDisk.class::isInstance).map(BrakeDisk.class::cast)
                .filter(d -> Cps1SymbolicDiameterITCase.BRAKE_DISC_ID.equals(d.getId())).findFirst().orElseThrow();
        String diskHandle = new IdAttributeHandleStrategy().createHandle(disk);
        List<String> invariants =
                Cps1ObservedOclITCase.invariants(Cps1SymbolicDiameterITCase.CPS1.resolve("overlapping.ocl"));
        Map<String, EPackage> packages = new LinkedHashMap<>();
        packages.put("cad", cad.CadPackage.eINSTANCE);
        packages.put("brakesystem", brakesystem.BrakesystemPackage.eINSTANCE);
        VitruvTestViewDefinition def = new VitruvTestViewDefinition(
                vsumState, Cps1SymbolicDiameterITCase.CPS, Cps1SymbolicDiameterITCase.ROOT_TYPES);

        long tAll = System.nanoTime();
        List<PathResult> paths = new ArrayList<>();
        Set<String> issuedFlips = new HashSet<>();
        Deque<Integer> work = new ArrayDeque<>();
        work.add(NOMINAL);
        try (Mode2Solver solver = new Mode2Solver(Integer.getInteger("mode2.solverPort", 9411))) {
            while (!work.isEmpty() && paths.size() < MAX_PATHS) {
                int input = work.poll();
                PathResult p = runPath(util, def, diskHandle, invariants, packages, input);
                paths.add(p);
                System.out.printf("[mode2-explore] path %d: d=%d, %d branches, verdicts %s, %d ms%n", paths.size(),
                        input, p.branches.size(), p.verdicts, p.millis);
                for (int i = 0; i < p.branches.size(); i++) {
                    System.out.println("[mode2-explore]   b" + i + " = " + Exprs.brief(p.branches.get(i), 12));
                }
                for (int i = 0; i < p.branches.size(); i++) {
                    String key = Exprs.brief(conj(p.branches, i), 40) + " | NOT " + Exprs.brief(p.branches.get(i), 40);
                    if (!issuedFlips.add(key)) {
                        continue;
                    }
                    Expression q = and(and(domain(), conj(p.branches, i)),
                            new UnaryOperation(Operator.NOT, p.branches.get(i)));
                    Mode2Solver.Result r = solver.check(q);
                    if (r.sat()) {
                        int next = Integer.parseInt(r.model.get("diameterInMM"));
                        boolean known = paths.stream().anyMatch(x -> x.input == next) || work.contains(next)
                                || coveredByExploredPath(solver, paths, next);
                        System.out.println("[mode2-explore]   flip b" + i + " -> SAT d=" + next + (known ? " (known)" : ""));
                        if (!known) {
                            work.add(next);
                        }
                    } else {
                        System.out.println("[mode2-explore]   flip b" + i + " -> " + r.status);
                    }
                }
            }

            // Result extents per path
            for (PathResult p : paths) {
                Expression pc = and(domain(), conj(p.branches, p.branches.size()));
                p.extent = extent(solver, pc);
            }
            long msAll = (System.nanoTime() - tAll) / 1_000_000;

            System.out.println("[mode2-result] ==== CPS1 Mode 2 result ====");
            for (PathResult p : paths) {
                System.out.println("[mode2-result] extent " + p.extent + " : " + (p.consistent() ? "CONSISTENT" : "INCONSISTENT")
                        + " " + p.verdicts + " (representative d=" + p.input + ")");
            }
            System.out.printf("[mode2-result] concrete executions: %d (Mode 1: %d), solver queries: %d (%.0f ms), total %d ms%n",
                    paths.size(), HI - LO + 1, solver.queries, solver.nanos / 1e6, msAll);

            // Agreement with Mode 1 on all 13 points: consistent exactly for d <= 329.
            for (int d = LO; d <= HI; d++) {
                final int dd = d;
                List<PathResult> covering = new ArrayList<>();
                for (PathResult p : paths) {
                    Expression pc = and(domain(), conj(p.branches, p.branches.size()));
                    if (solver.check(and(pc, new BinaryOperation(Operator.EQ, D, new BVConstant(dd, 32)))).sat()) {
                        covering.add(p);
                    }
                }
                Assertions.assertEquals(1, covering.size(), "every domain point must lie in exactly one path, d=" + d);
                Assertions.assertEquals(d <= 329, covering.get(0).consistent(), "agreement with Mode 1 at d=" + d);
            }
            System.out.println("[mode2-result] agreement with Mode 1 on all " + (HI - LO + 1) + " points: OK");
        }
    }

    /**
     * True if the input already lies in the region of an explored path (its path condition
     * holds for it), so executing it would only repeat that path. One solver query per path.
     */
    static boolean coveredByExploredPath(Mode2Solver solver, List<PathResult> paths, int input) throws Exception {
        for (PathResult p : paths) {
            Expression pc = and(domain(), conj(p.branches, p.branches.size()));
            if (solver.check(and(pc, new BinaryOperation(Operator.EQ, D, new BVConstant(input, 32)))).sat()) {
                return true;
            }
        }
        return false;
    }

    /** Interval [lo, hi] if the path condition (over D, within the domain) is exactly that interval. */
    static String extent(Mode2Solver solver, Expression pc) throws Exception {
        if (!solver.check(pc).sat()) {
            return "empty";
        }
        int lo = LO;
        int hi = HI;
        // smallest d with pc && d <= m
        int a = LO;
        int b = HI;
        while (a < b) {
            int m = (a + b) / 2;
            if (solver.check(and(pc, new BinaryOperation(Operator.LE, D, new BVConstant(m, 32)))).sat()) {
                b = m;
            } else {
                a = m + 1;
            }
        }
        lo = a;
        a = LO;
        b = HI;
        while (a < b) {
            int m = (a + b + 1) / 2;
            if (solver.check(and(pc, new BinaryOperation(Operator.GE, D, new BVConstant(m, 32)))).sat()) {
                a = m;
            } else {
                b = m - 1;
            }
        }
        hi = a;
        Expression interval = and(new BinaryOperation(Operator.GE, D, new BVConstant(lo, 32)),
                new BinaryOperation(Operator.LE, D, new BVConstant(hi, 32)));
        boolean pcInInterval = !solver.check(and(pc, new UnaryOperation(Operator.NOT, interval))).sat();
        boolean intervalInPc = !solver.check(and(and(domain(), interval), new UnaryOperation(Operator.NOT, pc))).sat();
        return pcInInterval && intervalInPc ? "d in [" + lo + ", " + hi + "]" : "predicate (hull [" + lo + ", " + hi + "])";
    }

    static PathResult runPath(VsumUtils util, VitruvTestViewDefinition def, String diskHandle, List<String> invariants,
            Map<String, EPackage> packages, int input) {
        PathResult p = new PathResult(input);
        long t0 = System.nanoTime();
        try (VsumViewSession session =
                new CopyingVitruvViewProvider(util, new IdBasedVsumObjectResolver()).checkoutIsolated(def)) {
            BrakeDisk d = (BrakeDisk) session.resolve(diskHandle);
            if (d.getDiameterInMM() == input) {
                // Setting the current value is an EMF "touch": Vitruvius records no change and
                // no CPR runs, so the symbolic value would never propagate. Change it to another
                // concrete value first; both changes are propagated in the same commit.
                d.setDiameterInMM(input == HI ? input - 1 : input + 1);
            }
            SymbolicListener.setListener(new PathConstraintListener());
            d.setDiameterInMM(Symbolicator.symbolicInRange("diameterInMM", input, LO, HI));
            session.commit();
            BrakeDisk committed = (BrakeDisk) session.resolve(diskHandle);
            InMemoryMetamodelWrapper wrapper = new InMemoryMetamodelWrapper(
                    packages, committed.eResource().getResourceSet().getResources());
            for (String inv : invariants) {
                int at = inv.indexOf("inv ") + 4;
                String name = inv.substring(at, inv.indexOf(':', at)).trim();
                OCLCompiler compiler = new OCLCompiler(wrapper, null);
                Value v = compiler.compile(inv);
                p.verdicts.put(name, !compiler.hasErrors() && verdict(v));
            }
            SymbolicListener.setListener(null);
            p.branches.addAll(PathUtils.getCurPC().branchConstraints);
        } finally {
            SymbolicListener.setListener(null);
            Symbolicator.reset();
        }
        p.millis = (System.nanoTime() - t0) / 1_000_000;
        return p;
    }
}
