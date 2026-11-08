package uncertaintree.mode2;

import edu.gmu.swe.knarr.runtime.OverflowMonitor;
import edu.gmu.swe.knarr.runtime.PathConstraintListener;
import edu.gmu.swe.knarr.runtime.PathUtils;
import edu.gmu.swe.knarr.runtime.Symbolicator;
import edu.neu.ccs.prl.galette.internal.runtime.symbolic.SymbolicListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.multimodelocl.evaluator.Value;
import tools.vitruv.multimodelocl.pipeline.OCLCompiler;
import uncertainty.Uncertainty;
import uncertainty.UncertaintyAnnotationRepository;
import uncertaintree.stateextraction.PossibleValue;
import uncertaintree.stateextraction.ResolvedUncertaintyDimension;
import uncertaintree.stateextraction.UncertaintyDimensionResolver;
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
 * Mode 2 of UncertainTree: symbolic execution of consistency preservation.
 *
 * <p>Inputs are exactly Mode 1's: the nominal VSUM folder, the change propagation
 * specifications, the view root types (including {@code UncertaintyAnnotationRepository})
 * and the OCL constraints. Nothing about the case study is coded here.
 *
 * <p>Per path: check out an isolated VSUM copy (as Mode 1 does), set every uncertain
 * attribute to a symbolic value with the annotation's domain, commit (Vitruvius propagates
 * through the real Reactions CPRs), evaluate the OCL constraints on the live model
 * (MultiModelOCL, unchanged). Branches of the CPRs and of the evaluator form the path
 * condition (requires the Galette branch scope, e.g.
 * {@code -Dgalette.symbolic.branchPrefixes=mir/,tools/vitruv/multimodelocl/}).
 *
 * <p>Exploration: DART-style flips; Z3 in a separate plain JVM ({@link Mode2SolverServer}).
 * Representatives differ from the nominal value wherever the domain allows it: setting
 * the current value is an EMF touch, which Vitruvius does not propagate. Points that are
 * only reachable at the nominal value are executed with that attribute left unchanged and
 * recorded with an explicit {@code x == nominal} constraint. Supported: int-valued
 * discrete ranges; other uncertainties stay at their nominal value and are reported.
 */
public final class Mode2Engine {

    /** Analysis inputs (Mode 1's, plus where to write the result). */
    public static final class Config {
        public String scenario;
        public Path vsumState;
        public List<ChangePropagationSpecification> cps;
        public List<Class<?>> rootTypes;
        public Path oclFile;
        public int solverPort = 9411;
        public int maxPaths = 40;
    }

    private static final class PathRun {
        final Map<String, Integer> input;
        final List<Expression> branches = new ArrayList<>();
        final Map<String, Boolean> verdicts = new LinkedHashMap<>();
        final List<Mode2Result.Affected> affected = new ArrayList<>();
        long millis;
        int overflows;
        String overflow;

        PathRun(Map<String, Integer> input) {
            this.input = input;
        }
    }

    private final Config config;
    private final VsumUtils util = new VsumUtils();
    private final IdAttributeHandleStrategy handles = new IdAttributeHandleStrategy();
    private final List<UncertaintySource> sources = new ArrayList<>();
    private final Mode2Result result = new Mode2Result();
    private List<String> invariants;
    private Map<String, EPackage> packages;
    private Mode2Solver solver;

    public Mode2Engine(Config config) {
        this.config = config;
    }

    public List<UncertaintySource> sources() {
        return sources;
    }

    public Mode2Result analyze() throws Exception {
        long t0 = System.nanoTime();
        result.scenario = config.scenario;
        discoverSources();
        invariants = invariants(config.oclFile);

        List<PathRun> paths = new ArrayList<>();
        Map<Map<String, Integer>, Expression> expected = new LinkedHashMap<>();
        Set<String> issuedFlips = new HashSet<>();
        Deque<Map<String, Integer>> work = new ArrayDeque<>();
        work.add(initialInput());
        try (Mode2Solver s = new Mode2Solver(config.solverPort)) {
            solver = s;
            while (paths.size() < config.maxPaths) {
                if (work.isEmpty()) {
                    Map<String, Integer> uncovered = uncoveredPoint(paths);
                    if (uncovered == null) {
                        break;
                    }
                    work.add(uncovered);
                }
                Map<String, Integer> input = work.poll();
                if (covered(paths, input)) {
                    continue;
                }
                PathRun p = runPath(input);
                paths.add(p);
                Expression predicted = expected.remove(input);
                if (predicted != null && sat(and(pc(p), not(predicted)))) {
                    result.stats.divergences++;
                }
                for (int i = 0; i < p.branches.size(); i++) {
                    String key = Exprs.brief(conj(p.branches, i), 40) + " | NOT " + Exprs.brief(p.branches.get(i), 40);
                    if (!issuedFlips.add(key)) {
                        continue;
                    }
                    Expression flip = and(conj(p.branches, i), not(p.branches.get(i)));
                    Map<String, Integer> next = solve(and(and(domain(), flip), preferNonNominal()));
                    if (next == null) {
                        next = solve(and(domain(), flip));
                    }
                    if (next != null && !covered(paths, next) && !work.contains(next)) {
                        work.add(next);
                        expected.put(next, flip);
                    }
                }
            }
            for (PathRun p : paths) {
                result.regions.add(region(p));
                result.stats.pathMillis.add(p.millis);
            }
            nominalCheck(paths);
            result.stats.concreteExecutions = paths.size();
            result.stats.solverQueries = solver.queries;
            result.stats.solverMillis = solver.nanos / 1_000_000;
        } finally {
            solver = null;
        }
        result.stats.totalMillis = (System.nanoTime() - t0) / 1_000_000;
        return result;
    }

    // ---------------------------------------------------------------- sources

    private void discoverSources() throws Exception {
        VirtualModel baseline = util.createDefaultVirtualModel(config.vsumState, config.cps);
        View view = util.getDefaultView(baseline, config.rootTypes);
        UncertaintyAnnotationRepository repository =
                view.getRootObjects(UncertaintyAnnotationRepository.class).iterator().next();
        packages = packagesOf(view);
        UncertaintyDimensionResolver resolver = new UncertaintyDimensionResolver();
        Set<String> labels = new HashSet<>();
        for (Uncertainty u : repository.getUncertainties()) {
            if (u == null || u.getUncertaintyLocation() == null) {
                continue;
            }
            for (EObject element : u.getUncertaintyLocation().getReferencedComponents()) {
                ResolvedUncertaintyDimension dim = resolver.resolve(u, element);
                String what = u.getId() + " on " + safeHandle(element);
                if (dim == null || dim.getAttributeName() == null) {
                    result.unsupported.add(what + ": not an attribute dimension (kept nominal)");
                    continue;
                }
                Class<?> type = dim.getJavaType();
                if (type != int.class && type != Integer.class) {
                    result.unsupported.add(what + ": type " + type + " not yet symbolic (kept nominal)");
                    continue;
                }
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (PossibleValue v : dim.getPossibleValues()) {
                    int x = ((Number) v.getValue()).intValue();
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
                EStructuralFeature f = element.eClass().getEStructuralFeature(dim.getAttributeName());
                int nominal = ((Number) element.eGet(f)).intValue();
                String label = uniqueLabel(u.getId() + "_" + dim.getAttributeName(), labels);
                UncertaintySource src = new UncertaintySource(
                        label, u.getId(), handles.createHandle(element), dim.getAttributeName(), min, max, nominal);
                sources.add(src);
                Mode2Result.Source rs = new Mode2Result.Source();
                rs.variable = label;
                rs.uncertainty = u.getId();
                rs.element = src.handle;
                rs.attribute = src.attribute;
                rs.min = min;
                rs.max = max;
                rs.nominal = nominal;
                result.sources.add(rs);
            }
        }
    }

    private String safeHandle(EObject o) {
        try {
            return handles.createHandle(o);
        } catch (RuntimeException e) {
            return o.eClass().getName();
        }
    }

    private static String uniqueLabel(String raw, Set<String> used) {
        String base = raw.replaceAll("[^A-Za-z0-9_]", "_");
        String l = base;
        for (int i = 2; !used.add(l); i++) {
            l = base + "_" + i;
        }
        return l;
    }

    private static Map<String, EPackage> packagesOf(View view) {
        Map<String, EPackage> map = new LinkedHashMap<>();
        for (EObject root : view.getRootObjects()) {
            map.putIfAbsent(root.eClass().getEPackage().getName(), root.eClass().getEPackage());
            for (TreeIterator<EObject> it = root.eAllContents(); it.hasNext(); ) {
                EPackage p = it.next().eClass().getEPackage();
                map.putIfAbsent(p.getName(), p);
            }
        }
        return map;
    }

    // ---------------------------------------------------------------- one path

    private PathRun runPath(Map<String, Integer> input) {
        PathRun p = new PathRun(input);
        long t0 = System.nanoTime();
        VitruvTestViewDefinition def = new VitruvTestViewDefinition(config.vsumState, config.cps, config.rootTypes);
        try (VsumViewSession session =
                new CopyingVitruvViewProvider(util, new IdBasedVsumObjectResolver()).checkoutIsolated(def)) {
            SymbolicListener.setListener(new PathConstraintListener());
            List<Expression> fixed = new ArrayList<>();
            EObject any = null;
            for (UncertaintySource src : sources) {
                EObject element = session.resolve(src.handle);
                any = element;
                int v = input.get(src.label);
                if (v == src.nominal) {
                    // EMF touch: no change, no propagation. Leave the value and record x == nominal.
                    fixed.add(new BinaryOperation(Operator.EQ, var(src), new BVConstant(v, 32)));
                    continue;
                }
                EStructuralFeature f = element.eClass().getEStructuralFeature(src.attribute);
                element.eSet(f, Symbolicator.symbolicInRange(src.label, v, src.min, src.max));
            }
            session.commit();
            EObject committed = any == null ? null : session.resolve(sources.get(0).handle);
            Collection<Resource> resources = committed == null
                    ? List.of()
                    : committed.eResource().getResourceSet().getResources();
            InMemoryMetamodelWrapper wrapper = new InMemoryMetamodelWrapper(packages, resources);
            for (String inv : invariants) {
                int at = inv.indexOf("inv ") + 4;
                String name = inv.substring(at, inv.indexOf(':', at)).trim();
                OCLCompiler compiler = new OCLCompiler(wrapper, null);
                Value v = compiler.compile(inv);
                p.verdicts.put(name, !compiler.hasErrors() && verdict(v));
            }
            SymbolicListener.setListener(null);
            p.branches.addAll(fixed);
            p.branches.addAll(PathUtils.getCurPC().branchConstraints);
            p.affected.addAll(affected(resources));
            p.overflows = OverflowMonitor.count();
            p.overflow = OverflowMonitor.first();
        } finally {
            SymbolicListener.setListener(null);
            Symbolicator.reset();
        }
        p.millis = (System.nanoTime() - t0) / 1_000_000;
        return p;
    }

    /** Attributes whose current value carries a symbolic expression (dependence on an uncertain variable). */
    private List<Mode2Result.Affected> affected(Collection<Resource> resources) {
        List<Mode2Result.Affected> list = new ArrayList<>();
        Set<String> sourceKeys = new HashSet<>();
        for (UncertaintySource s : sources) {
            sourceKeys.add(s.handle + "#" + s.attribute);
        }
        for (Resource r : resources) {
            for (TreeIterator<EObject> it = r.getAllContents(); it.hasNext(); ) {
                EObject o = it.next();
                for (EAttribute a : o.eClass().getEAllAttributes()) {
                    if (a.isMany()) {
                        continue;
                    }
                    Object v = o.eGet(a);
                    Expression e = null;
                    if (v instanceof Integer) {
                        e = Symbolicator.getExpression(((Integer) v).intValue());
                    } else if (v instanceof Float) {
                        e = Symbolicator.getExpression(((Float) v).floatValue());
                    } else if (v instanceof Double) {
                        e = Symbolicator.getExpression(((Double) v).doubleValue());
                    } else if (v instanceof Long) {
                        e = Symbolicator.getExpression(((Long) v).longValue());
                    }
                    if (e == null) {
                        continue;
                    }
                    String element = safeHandle(o);
                    if (sourceKeys.contains(element + "#" + a.getName())) {
                        continue;
                    }
                    Mode2Result.Affected af = new Mode2Result.Affected();
                    af.element = element;
                    af.attribute = a.getName();
                    af.expression = Exprs.brief(e, 8);
                    list.add(af);
                }
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- regions and solver

    private Mode2Result.Region region(PathRun p) throws Exception {
        Mode2Result.Region r = new Mode2Result.Region();
        r.representative.putAll(p.input);
        r.verdicts.putAll(p.verdicts);
        r.consistent = p.verdicts.values().stream().allMatch(Boolean::booleanValue);
        for (Expression b : p.branches) {
            r.pathCondition.add(Exprs.brief(b, 10));
        }
        r.affected.addAll(p.affected);
        Expression pc = and(domain(), pc(p));
        Expression box = null;
        for (UncertaintySource s : sources) {
            int lo = bound(pc, s, true);
            int hi = bound(pc, s, false);
            r.intervals.put(s.label, new int[] {lo, hi});
            box = and(box, and(ge(s, lo), le(s, hi)));
        }
        boolean pcInBox = box == null || !sat(and(pc, not(box)));
        boolean boxInPc = box == null || !sat(and(and(domain(), box), not(pc)));
        r.extentType = pcInBox && boxInPc ? "box" : "predicate";
        if (p.overflows > 0) {
            r.approximate = true;
            r.approximateReason = p.overflows + " overflow(s) on symbolic values, first: " + p.overflow;
        }
        return r;
    }

    /**
     * Evaluates the nominal state on the unchanged model and compares it with the region that
     * contains the nominal point. Different verdicts mean the nominal state is not a fixpoint of
     * the CPRs; the nominal point then becomes a region of its own.
     */
    private void nominalCheck(List<PathRun> paths) throws Exception {
        Map<String, Integer> nominal = new LinkedHashMap<>();
        for (UncertaintySource s : sources) {
            nominal.put(s.label, s.nominal);
        }
        Map<String, Boolean> verdicts = new LinkedHashMap<>();
        VitruvTestViewDefinition def = new VitruvTestViewDefinition(config.vsumState, config.cps, config.rootTypes);
        try (VsumViewSession session =
                new CopyingVitruvViewProvider(util, new IdBasedVsumObjectResolver()).checkoutIsolated(def)) {
            Collection<Resource> resources = sources.isEmpty()
                    ? List.of()
                    : session.resolve(sources.get(0).handle).eResource().getResourceSet().getResources();
            InMemoryMetamodelWrapper wrapper = new InMemoryMetamodelWrapper(packages, resources);
            for (String inv : invariants) {
                int at = inv.indexOf("inv ") + 4;
                String name = inv.substring(at, inv.indexOf(':', at)).trim();
                OCLCompiler compiler = new OCLCompiler(wrapper, null);
                Value v = compiler.compile(inv);
                verdicts.put(name, !compiler.hasErrors() && verdict(v));
            }
        }
        Mode2Result.Nominal n = result.nominal;
        n.values.putAll(nominal);
        n.verdicts.putAll(verdicts);
        n.consistent = verdicts.values().stream().allMatch(Boolean::booleanValue);
        for (int i = 0; i < paths.size(); i++) {
            if (sat(and(and(domain(), pc(paths.get(i))), point(nominal)))) {
                n.region = i;
                break;
            }
        }
        n.fixpointConfirmed = n.region >= 0 && result.regions.get(n.region).verdicts.equals(verdicts);
        if (!n.fixpointConfirmed) {
            if (n.region >= 0) {
                Mode2Result.Region from = result.regions.get(n.region);
                from.excludesNominal = true;
                from.extentType = "predicate";
            }
            Mode2Result.Region point = new Mode2Result.Region();
            point.extentType = "box";
            for (UncertaintySource s : sources) {
                point.intervals.put(s.label, new int[] {s.nominal, s.nominal});
            }
            point.representative.putAll(nominal);
            point.verdicts.putAll(verdicts);
            point.consistent = n.consistent;
            point.pathCondition.add("nominal state (unchanged model)");
            result.regions.add(point);
            n.region = result.regions.size() - 1;
        }
    }

    /** Smallest (lower) or largest value of s within pc, by binary search with the solver. */
    private int bound(Expression pc, UncertaintySource s, boolean lower) throws Exception {
        int a = s.min;
        int b = s.max;
        while (a < b) {
            if (lower) {
                int m = Math.floorDiv(a + b, 2);
                if (sat(and(pc, le(s, m)))) {
                    b = m;
                } else {
                    a = m + 1;
                }
            } else {
                int m = Math.floorDiv(a + b + 1, 2);
                if (sat(and(pc, ge(s, m)))) {
                    a = m;
                } else {
                    b = m - 1;
                }
            }
        }
        return a;
    }

    private boolean covered(List<PathRun> paths, Map<String, Integer> input) throws Exception {
        Expression point = point(input);
        for (PathRun p : paths) {
            if (sat(and(and(domain(), pc(p)), point))) {
                return true;
            }
        }
        return false;
    }

    /** A domain point outside all explored regions, or null if the regions cover the domain. */
    private Map<String, Integer> uncoveredPoint(List<PathRun> paths) throws Exception {
        Expression outside = domain();
        for (PathRun p : paths) {
            outside = and(outside, not(pc(p)));
        }
        Map<String, Integer> m = solve(and(outside, preferNonNominal()));
        return m != null ? m : solve(outside);
    }

    private Map<String, Integer> initialInput() {
        Map<String, Integer> in = new LinkedHashMap<>();
        for (UncertaintySource s : sources) {
            in.put(s.label, s.nominal != s.min ? s.min : s.max);
        }
        return in;
    }

    private Map<String, Integer> solve(Expression q) throws Exception {
        Mode2Solver.Result r = solver.check(q);
        if (!r.sat()) {
            return null;
        }
        Map<String, Integer> in = new LinkedHashMap<>();
        for (UncertaintySource s : sources) {
            String v = r.model.get(s.label);
            in.put(s.label, v == null ? s.nominal : Integer.parseInt(v));
        }
        return in;
    }

    private boolean sat(Expression e) throws Exception {
        return solver.check(e).sat();
    }

    private Expression domain() {
        Expression d = null;
        for (UncertaintySource s : sources) {
            d = and(d, and(ge(s, s.min), le(s, s.max)));
        }
        return d == null ? new BinaryOperation(Operator.EQ, new BVConstant(0, 32), new BVConstant(0, 32)) : d;
    }

    private Expression preferNonNominal() {
        Expression d = null;
        for (UncertaintySource s : sources) {
            if (s.hasAlternatives()) {
                d = and(d, new BinaryOperation(Operator.NE, var(s), new BVConstant(s.nominal, 32)));
            }
        }
        return d;
    }

    private Expression point(Map<String, Integer> input) {
        Expression d = null;
        for (UncertaintySource s : sources) {
            d = and(d, new BinaryOperation(Operator.EQ, var(s), new BVConstant(input.get(s.label), 32)));
        }
        return d;
    }

    private static Expression pc(PathRun p) {
        return conj(p.branches, p.branches.size());
    }

    private static BVVariable var(UncertaintySource s) {
        return new BVVariable(s.label, 32);
    }

    private static Expression ge(UncertaintySource s, int v) {
        return new BinaryOperation(Operator.GE, var(s), new BVConstant(v, 32));
    }

    private static Expression le(UncertaintySource s, int v) {
        return new BinaryOperation(Operator.LE, var(s), new BVConstant(v, 32));
    }

    private static Expression not(Expression e) {
        return e == null ? null : new UnaryOperation(Operator.NOT, e);
    }

    private static Expression and(Expression a, Expression b) {
        return a == null ? b : (b == null ? a : new BinaryOperation(Operator.AND, a, b));
    }

    private static Expression conj(List<Expression> es, int upTo) {
        Expression acc = null;
        for (int i = 0; i < upTo; i++) {
            acc = and(acc, es.get(i));
        }
        return acc;
    }

    // ---------------------------------------------------------------- OCL

    /** Splits an OCL file into its "context ... inv ..." blocks. */
    public static List<String> invariants(Path oclFile) throws Exception {
        String text = Files.readString(oclFile);
        List<String> blocks = new ArrayList<>();
        int i = text.indexOf("context ");
        while (i >= 0) {
            int j = text.indexOf("\ncontext ", i + 1);
            blocks.add((j < 0 ? text.substring(i) : text.substring(i, j)).trim());
            i = j < 0 ? -1 : j + 1;
        }
        return blocks;
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
        return "true".equals(String.valueOf(o));
    }
}
