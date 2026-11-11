package uncertaintree.mode2;

import edu.gmu.swe.knarr.runtime.OverflowMonitor;
import edu.gmu.swe.knarr.runtime.PathConstraintListener;
import edu.gmu.swe.knarr.runtime.PathUtils;
import edu.gmu.swe.knarr.runtime.Symbolicator;
import edu.neu.ccs.prl.galette.internal.runtime.symbolic.SymbolicListener;
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
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import za.ac.sun.cs.green.expr.IntConstant;
import za.ac.sun.cs.green.expr.IntVariable;
import za.ac.sun.cs.green.expr.RealConstant;
import za.ac.sun.cs.green.expr.RealVariable;
import edu.neu.ccs.prl.galette.internal.runtime.Tag;
import edu.neu.ccs.prl.galette.internal.runtime.Tainter;
import za.ac.sun.cs.green.expr.BinaryOperation;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.Operation.Operator;
import za.ac.sun.cs.green.expr.UnaryOperation;

/**
 * Mode 2 of UncertainTree: symbolic execution of consistency preservation, independent of the
 * VSUM framework (see {@link Mode2Backend}).
 *
 * <p>Per path: check out an isolated copy of the nominal models, set every uncertain attribute
 * to a symbolic value with the annotation's domain, commit (the framework propagates through
 * the real CPRs), evaluate the OCL constraints on the live model. Branches of the CPRs and of
 * the evaluator form the path condition (requires the Galette branch scope, e.g.
 * {@code -Dgalette.symbolic.branchPrefixes=mir/,tools/vitruv/multimodelocl/}).
 *
 * <p>Exploration: DART-style flips; Z3 in a separate plain JVM ({@link Mode2SolverServer}).
 * Representatives differ from the nominal value wherever the domain allows it: setting
 * the current value is an EMF touch, which Vitruvius does not propagate. Points that are
 * only reachable at the nominal value are executed with that attribute left unchanged and
 * recorded with an explicit {@code x == nominal} constraint. Supported: int-valued
 * discrete ranges and real intervals; other uncertainties stay at their nominal value and are reported.
 */
public final class Mode2Analysis {

    /** Analysis options that do not depend on the backend. */
    public static class Options {
        public String scenario;
        public int solverPort = 9411;
        /** Solver process shared across analyses (the worker's); null = start one for this analysis. */
        public Mode2Solver solver;
        public int maxPaths = 40;
        /**
         * Changes that come in on the uncertain model (e.g. from a demonstrator). Applied after
         * the realization of the uncertain values and propagated in the same commit, so every
         * CPR they trigger reads the tagged values (see docs/mode2-concepts.md, entry 4).
         */
        public List<AttributeChange> incomingChanges = new ArrayList<>();
        /** Optional first input (variable label → value) instead of the default representative; diagnosis. */
        public Map<String, Number> firstInput;
        /**
         * Points to classify after the exploration (variable label → value; missing labels take the
         * nominal value), e.g. the candidates of a design study: each gets the region whose path
         * condition it satisfies, decided by the solver (exact also for non-box regions).
         */
        public List<Map<String, Number>> points = new ArrayList<>();
        /**
         * Whether region extents (intervals, slices, probabilities) are computed. A design study
         * needs only the regions' verdicts and the classification of its points, which uses the
         * path conditions directly; on nonlinear path conditions with several real sources the
         * bounds can take hours (demonstrator, 2026-11-11).
         */
        public boolean extents = true;
    }

    /** An attribute assignment on an element, identified by the backend's handle. */
    public static class AttributeChange {
        public String element;
        public String attribute;
        /** Value in EMF's literal form for the attribute type (EcoreUtil.createFromString). */
        public String value;

        public AttributeChange() {}

        public AttributeChange(String element, String attribute, String value) {
            this.element = element;
            this.attribute = attribute;
            this.value = value;
        }
    }

    private static void apply(Mode2Backend.Session session, List<? extends AttributeChange> changes) {
        for (AttributeChange c : changes) {
            EObject o = session.resolve(c.element);
            EStructuralFeature f = o.eClass().getEStructuralFeature(c.attribute);
            if (!(f instanceof EAttribute)) {
                throw new IllegalArgumentException("not an attribute: " + c.element + "." + c.attribute);
            }
            o.eSet(f, org.eclipse.emf.ecore.util.EcoreUtil.createFromString(((EAttribute) f).getEAttributeType(), c.value));
        }
    }

    private static final class PathRun {
        final Map<String, Number> input;
        final List<Expression> branches = new ArrayList<>();
        final Map<String, Boolean> verdicts = new LinkedHashMap<>();
        final List<Mode2Result.Affected> affected = new ArrayList<>();
        /** Per annotated attribute (index into annotated): status and expression after propagation. */
        final Map<Integer, String[]> annotationStates = new LinkedHashMap<>();
        /** Expressions behind "rewritten" states, for the identity check. */
        final Map<Integer, Expression> annotationExprs = new LinkedHashMap<>();
        Map<String, Integer> structure = Map.of();
        long millis;
        int overflows;
        String overflow;

        PathRun(Map<String, Number> input) {
            this.input = input;
        }
    }

    private final Mode2Backend backend;
    private final Options config;
    private final List<UncertaintySource> sources = new ArrayList<>();
    private final Mode2Result result = new Mode2Result();
    /** Every annotated attribute dimension (symbolic or not), for the dependency checks. */
    private final List<Mode2Result.AnnotationCheck> annotated = new ArrayList<>();
    /** For each entry of annotated: the source it is (or null if not symbolic). */
    private final List<UncertaintySource> annotatedSource = new ArrayList<>();
    private Map<String, Integer> nominalStructure = Map.of();
    private Mode2Solver solver;

    public Mode2Analysis(Mode2Backend backend, Options options) {
        this.backend = backend;
        this.config = options;
    }

    private void phase(String name, long startNanos) {
        result.stats.phaseMillis.merge(name, (System.nanoTime() - startNanos) / 1_000_000, Long::sum);
    }

    /** Timing sink for invariant evaluation: "&lt;prefix&gt;ocl.&lt;invariant&gt;" and "ocl.&lt;prefix&gt;&lt;invariant&gt;.&lt;stage&gt;". */
    private Mode2Backend.Timing timing(String prefix) {
        return new Mode2Backend.Timing() {
            @Override
            public void invariant(String invariant, long nanos) {
                result.stats.phaseMillis.merge("path.ocl." + invariant, nanos / 1_000_000, Long::sum);
            }

            @Override
            public void stage(String invariant, String stage, long nanos) {
                result.stats.phaseMillis.merge("ocl." + prefix + invariant + "." + stage, nanos / 1_000_000, Long::sum);
            }
        };
    }

    private Mode2Backend.Timing nominalTiming() {
        Mode2Backend.Timing t = timing("nominal.");
        return new Mode2Backend.Timing() {
            @Override
            public void invariant(String invariant, long nanos) {
                // the nominal evaluation had no per-invariant phase before the backend split
            }

            @Override
            public void stage(String invariant, String stage, long nanos) {
                t.stage(invariant, stage, nanos);
            }
        };
    }

    public List<UncertaintySource> sources() {
        return sources;
    }

    public Mode2Result analyze() throws Exception {
        long t0 = System.nanoTime();
        result.scenario = config.scenario;
        long ts = System.nanoTime();
        discoverSources();
        phase("discoverSources", ts);
        ts = System.nanoTime();

        List<PathRun> paths = new ArrayList<>();
        Map<Map<String, Number>, Expression> expected = new LinkedHashMap<>();
        Set<String> issuedFlips = new HashSet<>();
        Deque<Map<String, Number>> work = new ArrayDeque<>();
        work.add(config.firstInput != null ? new LinkedHashMap<>(config.firstInput) : initialInput());
        // A solver process started per analysis runs Green's translation and Z3's Java API
        // cold (about 130 ms per query); the worker passes one process for its lifetime.
        Mode2Solver own = config.solver == null ? new Mode2Solver(config.solverPort) : null;
        Mode2Solver s = own != null ? own : config.solver;
        int queries0 = s.queries;
        int unknowns0 = s.unknowns;
        long nanos0 = s.nanos;
        long write0 = s.writeNanos;
        long reply0 = s.replyNanos;
        try {
            phase("solverStart", ts);
            solver = s;
            while (paths.size() < config.maxPaths) {
                if (work.isEmpty()) {
                    Map<String, Number> uncovered = uncoveredPoint(paths);
                    if (uncovered == null) {
                        break;
                    }
                    if (covered(paths, uncovered)) {
                        // The solver's point outside every region, with its reals as truncated
                        // decimals, lies inside an explored region: what is left uncovered is a
                        // sliver at a boundary, which this exploration cannot hit. Without this
                        // check the same query repeated forever (2.4 million queries, 2026-11-11).
                        result.stats.coverageGaps++;
                        break;
                    }
                    work.add(uncovered);
                }
                Map<String, Number> input = work.poll();
                if (covered(paths, input)) {
                    continue;
                }
                PathRun p = runPath(input);
                paths.add(p);
                System.out.println("[mode2] path " + paths.size() + " " + input + ": " + p.millis + " ms, "
                        + p.branches.size() + " branches");
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
                    Map<String, Number> next = solve(and(and(domain(), flip), preferNonNominal()));
                    if (next == null) {
                        next = solve(and(domain(), flip));
                    }
                    if (next != null && !covered(paths, next) && !work.contains(next)) {
                        work.add(next);
                        expected.put(next, flip);
                    }
                }
            }
            long tr = System.nanoTime();
            for (PathRun p : paths) {
                long tg = System.nanoTime();
                result.regions.add(region(p));
                System.out.println("[mode2] region " + result.regions.size() + " of " + paths.size() + ": "
                        + (System.nanoTime() - tg) / 1_000_000 + " ms");
                result.stats.pathMillis.add(p.millis);
            }
            phase("regions", tr);
            long tn = System.nanoTime();
            nominalCheck(paths);
            phase("nominalCheck", tn);
            for (int i = 0; i < paths.size(); i++) {
                diffStructure(result.regions.get(i), paths.get(i).structure);
            }
            varyingStructure(paths);
            summarizeAnnotations(paths);
            verdictClasses();
            long tp = System.nanoTime();
            classify(paths);
            phase("classify", tp);
            result.stats.concreteExecutions = paths.size();
            result.stats.solverQueries = solver.queries - queries0;
            result.stats.solverUnknowns = solver.unknowns - unknowns0;
            result.stats.solverMillis = (solver.nanos - nanos0) / 1_000_000;
            result.stats.phaseMillis.put("solver.write", (solver.writeNanos - write0) / 1_000_000);
            result.stats.phaseMillis.put("solver.readReply", (solver.replyNanos - reply0) / 1_000_000);
        } finally {
            solver = null;
            if (own != null) {
                own.close();
            }
        }
        result.stats.totalMillis = (System.nanoTime() - t0) / 1_000_000;
        phase("analyze.total", t0);
        return result;
    }

    // ---------------------------------------------------------------- sources

    private void discoverSources() throws Exception {
        Set<String> labels = new HashSet<>();
        // annotations grouped by uncertainty, in order (real members of one uncertainty form one source)
        Map<String, List<Mode2Backend.Annotation>> byUncertainty = new LinkedHashMap<>();
        for (Mode2Backend.Annotation a : backend.annotations()) {
            byUncertainty.computeIfAbsent(a.uncertainty, k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<String, List<Mode2Backend.Annotation>> group : byUncertainty.entrySet()) {
            String uncertaintyId = group.getKey();
            List<UncertaintySource.Member> realMembers = new ArrayList<>();
            List<Mode2Result.AnnotationCheck> realChecks = new ArrayList<>();
            double realLo = Double.POSITIVE_INFINITY;
            double realHi = Double.NEGATIVE_INFINITY;
            for (Mode2Backend.Annotation dim : group.getValue()) {
                EObject element = dim.element;
                String what = uncertaintyId + " on " + safeHandle(element);
                if (dim.attribute == null) {
                    result.unsupported.add(what + ": not an attribute dimension (kept nominal)");
                    continue;
                }
                Class<?> type = dim.type;
                Mode2Result.AnnotationCheck check = new Mode2Result.AnnotationCheck();
                check.uncertainty = uncertaintyId;
                check.element = safeHandle(element);
                check.attribute = dim.attribute;
                annotated.add(check);
                if (type == float.class || type == Float.class || type == double.class || type == Double.class) {
                    // Real-valued (scenario C): collected per annotation; several elements form one
                    // shift variable (Mode 1's grouped dimension with a synchronized offset).
                    EStructuralFeature f = element.eClass().getEStructuralFeature(dim.attribute);
                    double nominal = ((Number) element.eGet(f)).doubleValue();
                    double lo = Double.POSITIVE_INFINITY;
                    double hi = Double.NEGATIVE_INFINITY;
                    for (Number v : dim.possibleValues) {
                        double x = v.doubleValue();
                        lo = Math.min(lo, x);
                        hi = Math.max(hi, x);
                    }
                    realMembers.add(new UncertaintySource.Member(backend.handle(element), dim.attribute, nominal));
                    realChecks.add(check);
                    // as offsets from the member's nominal value (identical for every member of a group)
                    realLo = Math.min(realLo, lo - nominal);
                    realHi = Math.max(realHi, hi - nominal);
                    annotatedSource.add(null); // replaced below once the source exists
                    continue;
                }
                if (type != int.class && type != Integer.class) {
                    result.unsupported.add(what + ": type " + type + " not yet symbolic (kept nominal)");
                    annotatedSource.add(null);
                    continue;
                }
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (Number v : dim.possibleValues) {
                    int x = v.intValue();
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
                EStructuralFeature f = element.eClass().getEStructuralFeature(dim.attribute);
                int nominal = ((Number) element.eGet(f)).intValue();
                String label = uniqueLabel(uncertaintyId + "_" + dim.attribute, labels);
                UncertaintySource src = new UncertaintySource(
                        label, uncertaintyId, backend.handle(element), dim.attribute, min, max, nominal);
                sources.add(src);
                check.symbolic = true;
                annotatedSource.add(src);
                Mode2Result.Source rs = new Mode2Result.Source();
                rs.variable = label;
                rs.uncertainty = uncertaintyId;
                rs.element = src.handle;
                rs.attribute = src.attribute;
                rs.min = min;
                rs.max = max;
                rs.nominal = nominal;
                rs.members.add(src.handle + "#" + src.attribute);
                result.sources.add(rs);
            }
            if (!realMembers.isEmpty()) {
                boolean offset = realMembers.size() > 1;
                UncertaintySource.Member m0 = realMembers.get(0);
                String label = uniqueLabel(uncertaintyId + "_" + (offset ? "shift" : m0.attribute), labels);
                UncertaintySource src = offset
                        ? new UncertaintySource(label, uncertaintyId, realMembers, true, realLo, realHi)
                        : new UncertaintySource(label, uncertaintyId, realMembers, false, m0.nominal + realLo, m0.nominal + realHi);
                sources.add(src);
                for (Mode2Result.AnnotationCheck c : realChecks) {
                    c.symbolic = true;
                    annotatedSource.set(annotated.indexOf(c), src);
                }
                Mode2Result.Source rs = new Mode2Result.Source();
                rs.variable = label;
                rs.uncertainty = uncertaintyId;
                rs.element = src.handle;
                rs.attribute = src.attribute;
                rs.type = "real";
                rs.offset = offset;
                rs.minReal = src.minReal;
                rs.maxReal = src.maxReal;
                rs.nominalReal = src.nominalReal;
                rs.min = src.min;
                rs.max = src.max;
                rs.nominal = src.nominal;
                for (UncertaintySource.Member m : realMembers) {
                    rs.members.add(m.handle + "#" + m.attribute);
                }
                result.sources.add(rs);
            }
        }
    }

    private String safeHandle(EObject o) {
        try {
            return backend.handle(o);
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


    // ---------------------------------------------------------------- one path

    private PathRun runPath(Map<String, Number> input) {
        PathRun p = new PathRun(input);
        long t0 = System.nanoTime();
        long tc = System.nanoTime();
        try (Mode2Backend.Session session = backend.checkout()) {
            phase("path.checkout", tc);
            tc = System.nanoTime();
            SymbolicListener.setListener(new PathConstraintListener());
            List<Expression> fixed = new ArrayList<>();
            EObject any = null;
            for (UncertaintySource src : sources) {
                EObject element = session.resolve(src.handle);
                any = element;
                Number v = input.get(src.label);
                if (v.doubleValue() == src.nominalReal) {
                    // EMF touch: no change, no propagation. Leave the value and record x == nominal.
                    fixed.add(new BinaryOperation(Operator.EQ, var(src), constant(src, v)));
                    continue;
                }
                if (!src.real) {
                    EStructuralFeature f = element.eClass().getEStructuralFeature(src.attribute);
                    element.eSet(f, Symbolicator.symbolicInRange(src.label, v.intValue(), src.min, src.max));
                    continue;
                }
                // Real source: every member gets its value with the tag "nominal_i + δ" (shift) or "x".
                for (UncertaintySource.Member m : src.members) {
                    EObject target = session.resolve(m.handle);
                    EStructuralFeature f = target.eClass().getEStructuralFeature(m.attribute);
                    double value = src.memberValue(m, v.doubleValue());
                    Tag tag = Tag.of(memberExpression(src, m));
                    Class<?> type = f.getEType().getInstanceClass();
                    target.eSet(f, type == float.class || type == Float.class
                            ? (Object) Tainter.setTag((float) value, tag)
                            : (Object) Tainter.setTag(value, tag));
                }
            }
            apply(session, config.incomingChanges);
            session.commit();
            phase("path.setAndCommit", tc);
            Collection<Resource> resources = any == null ? List.of() : session.resources();
            p.verdicts.putAll(session.evaluate(resources, timing("")));
            SymbolicListener.setListener(null);
            p.branches.addAll(fixed);
            p.branches.addAll(PathUtils.getCurPC().branchConstraints);
            long tt = System.nanoTime();
            p.affected.addAll(affected(resources));
            checkAnnotations(p, session);
            p.structure = structure(resources);
            phase("path.tagScan", tt);
            p.overflows = OverflowMonitor.count();
            p.overflow = OverflowMonitor.first();
        } finally {
            SymbolicListener.setListener(null);
            Symbolicator.reset();
        }
        p.millis = (System.nanoTime() - t0) / 1_000_000;
        phase("path.total", t0);
        return p;
    }

    /** Attributes whose current value carries a symbolic expression (dependence on an uncertain variable). */
    private List<Mode2Result.Affected> affected(Collection<Resource> resources) {
        List<Mode2Result.Affected> list = new ArrayList<>();
        Set<String> sourceKeys = new HashSet<>();
        for (UncertaintySource s : sources) {
            for (UncertaintySource.Member m : s.members) {
                sourceKeys.add(m.handle + "#" + m.attribute);
            }
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

    // ---------------------------------------------------------------- annotations and structure

    /** Records, for every annotated attribute, what its value carries after this path's propagation. */
    private void checkAnnotations(PathRun p, Mode2Backend.Session session) {
        for (int i = 0; i < annotated.size(); i++) {
            Mode2Result.AnnotationCheck c = annotated.get(i);
            UncertaintySource own = annotatedSource.get(i);
            if (own != null && p.input.get(own.label).doubleValue() == own.nominalReal) {
                continue; // not realized on this path (left at its nominal value, untagged)
            }
            EObject element;
            try {
                element = session.resolve(c.element);
            } catch (RuntimeException e) {
                continue; // no handle or element deleted: shows up in the structural comparison
            }
            EStructuralFeature f = element == null ? null : element.eClass().getEStructuralFeature(c.attribute);
            if (f == null) {
                continue;
            }
            Expression e = expressionOf(element.eGet(f));
            java.util.Set<String> vars = Exprs.variables(e);
            String status;
            if (e == null) {
                status = own != null ? "resolved" : "untouched";
            } else if (own != null && vars.equals(java.util.Set.of(own.label))) {
                status = Exprs.brief(e, 12).equals(Exprs.brief(expectedExpression(own, c.element), 12))
                        ? "independent" : "rewritten";
            } else {
                status = "derived";
            }
            p.annotationStates.put(i, new String[] {status, e == null ? null : Exprs.brief(e, 8), String.join(",", vars)});
            if ("rewritten".equals(status)) {
                p.annotationExprs.put(i, e);
            }
        }
    }

    private void summarizeAnnotations(List<PathRun> paths) throws Exception {
        List<String> order = List.of("derived", "resolved", "rewritten", "independent", "untouched");
        for (int i = 0; i < annotated.size(); i++) {
            Mode2Result.AnnotationCheck c = annotated.get(i);
            for (PathRun p : paths) {
                String[] st = p.annotationStates.get(i);
                if (st == null) {
                    continue;
                }
                c.paths.merge(st[0], 1, Integer::sum);
                if (st.length > 2 && !st[2].isEmpty()) {
                    c.dependsOn.addAll(List.of(st[2].split(",")));
                }
                if (c.expression == null && st[1] != null && ("derived".equals(st[0]) || "rewritten".equals(st[0]))) {
                    c.expression = st[1];
                }
            }
            c.status = order.stream().filter(c.paths::containsKey).findFirst().orElse("untouched");
            if ("rewritten".equals(c.status)) {
                // Cheap check (one query per path): is the rewritten value the variable itself
                // on the path's part of the domain? Then the rules wrote it back unchanged.
                UncertaintySource own = annotatedSource.get(i);
                boolean identity = true;
                for (PathRun p : paths) {
                    Expression e = p.annotationExprs.get(i);
                    if (e != null) {
                        identity &= !sat(and(and(domain(), pc(p)),
                                new BinaryOperation(Operator.NE, e, expectedExpression(own, c.element))));
                    }
                }
                c.identityOnDomain = identity;
            }
            result.annotationChecks.add(c);
        }
    }

    /** The tag the realization gave this annotated element's attribute (variable, or nominal_i + δ). */
    private static Expression expectedExpression(UncertaintySource s, String handle) {
        if (!s.real) {
            return var(s);
        }
        for (UncertaintySource.Member m : s.members) {
            if (m.handle.equals(handle)) {
                return memberExpression(s, m);
            }
        }
        return var(s);
    }

    private static Expression expressionOf(Object v) {
        if (v instanceof Integer) {
            return Symbolicator.getExpression(((Integer) v).intValue());
        } else if (v instanceof Float) {
            return Symbolicator.getExpression(((Float) v).floatValue());
        } else if (v instanceof Double) {
            return Symbolicator.getExpression(((Double) v).doubleValue());
        } else if (v instanceof Long) {
            return Symbolicator.getExpression(((Long) v).longValue());
        }
        return null;
    }

    /** Multiset of the elements of the models, keyed by "EClass handle" (uncertainty model excluded). */
    private Map<String, Integer> structure(Collection<Resource> resources) {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (Resource r : resources) {
            for (TreeIterator<EObject> it = r.getAllContents(); it.hasNext(); ) {
                EObject o = it.next();
                if (!backend.inStructure(o)) {
                    continue;
                }
                String h = safeHandle(o);
                String key = h.equals(o.eClass().getName()) ? h : o.eClass().getName() + " " + h;
                m.merge(key, 1, Integer::sum);
            }
        }
        return m;
    }

    private void diffStructure(Mode2Result.Region r, Map<String, Integer> path) {
        if (nominalStructure.isEmpty()) {
            return;
        }
        Set<String> keys = new java.util.TreeSet<>(path.keySet());
        keys.addAll(nominalStructure.keySet());
        for (String k : keys) {
            int d = path.getOrDefault(k, 0) - nominalStructure.getOrDefault(k, 0);
            for (int j = 0; j < Math.abs(d); j++) {
                (d > 0 ? r.elementsAdded : r.elementsRemoved).add(k);
            }
        }
    }

    /** Elements whose count differs between the paths' committed models (UCM10 step 2). */
    private void varyingStructure(List<PathRun> paths) {
        Set<String> keys = new java.util.TreeSet<>();
        for (PathRun p : paths) {
            keys.addAll(p.structure.keySet());
        }
        for (String k : keys) {
            Set<Integer> counts = new HashSet<>();
            for (PathRun p : paths) {
                counts.add(p.structure.getOrDefault(k, 0));
            }
            if (counts.size() > 1) {
                result.elementsVaryingBetweenRegions.add(k);
            }
        }
        int n = result.nominal.region;
        if (n >= 0 && n < paths.size()) {
            Mode2Result.Region r = result.regions.get(n);
            result.nominal.structureDiffers = !r.elementsAdded.isEmpty() || !r.elementsRemoved.isEmpty();
        }
    }

    /** Probability of the single nominal point: 0 as soon as one variable is real (measure zero). */
    private double nominalPointProbability() {
        return sources.stream().anyMatch(x -> x.real) ? 0.0 : 1 / domainSize();
    }

    private double domainSize() {
        double n = 1;
        for (UncertaintySource s : sources) {
            n *= s.max - s.min + 1;
        }
        return n;
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
        if (!config.extents) {
            r.extentType = "none";
            if (p.overflows > 0) {
                r.approximate = true;
                r.approximateReason = p.overflows + " overflow(s) on symbolic values, first: " + p.overflow;
            }
            return r;
        }
        Expression pc = and(domain(), pc(p));
        Expression box = null;
        for (UncertaintySource s : sources) {
            if (s.real) {
                Mode2Result.RealInterval iv = realBounds(pc, s);
                r.realIntervals.put(s.label, iv);
                box = and(box, realBox(s, iv));
                continue;
            }
            int lo;
            int hi;
            // Default: binary search. On a warm solver it beat Z3 Optimize for scenario A
            // (1.3 s vs 2.5 s for all queries; Optimize is slow on the nonlinear path conditions);
            // Optimize needs two calls whatever the width, so it wins for wide domains.
            boolean search = !"optimize".equals(System.getProperty("mode2.bounds"));
            Mode2Solver.Result b = search ? null : solver.bounds(pc, s.label);
            if (b != null && b.sat()) {
                lo = Integer.parseInt(b.model.get("min"));
                hi = Integer.parseInt(b.model.get("max"));
            } else {
                // optimizer failed (an ERROR status): binary search with plain checks
                if (b != null) {
                    System.out.println("[mode2] optimizer bounds failed (" + b.status + "), binary search for " + s.label);
                }
                lo = bound(pc, s, true);
                hi = bound(pc, s, false);
            }
            r.intervals.put(s.label, new int[] {lo, hi});
            box = and(box, and(ge(s, lo), le(s, hi)));
        }
        boolean pcInBox = box == null || !sat(and(pc, not(box)));
        boolean boxInPc = box == null || !sat(and(and(domain(), box), not(pc)));
        r.extentType = pcInBox && boxInPc ? "box" : "predicate";
        if ("box".equals(r.extentType)) {
            double share = 1;
            for (UncertaintySource s : sources) {
                share *= s.real ? r.realIntervals.get(s.label).length() / (s.maxReal - s.minReal)
                        : (double) (r.intervals.get(s.label)[1] - r.intervals.get(s.label)[0] + 1) / (s.max - s.min + 1);
            }
            r.probability = share;
        } else if (!r.realIntervals.isEmpty() && !r.intervals.isEmpty()) {
            slices(r, pc, p);
        }
        if (p.overflows > 0) {
            r.approximate = true;
            r.approximateReason = p.overflows + " overflow(s) on symbolic values, first: " + p.overflow;
        }
        return r;
    }

    /**
     * Bounds of a real variable within pc, with open or closed ends: Z3 Optimize (exact for linear
     * path conditions); if it fails (nonlinear), bisection to a tolerance, marked approximate. The
     * bounds only serve display and probabilities; exploration uses the path condition (concepts 16).
     */
    private Mode2Result.RealInterval realBounds(Expression pc, UncertaintySource s) throws Exception {
        if (!Exprs.variables(pc).contains(s.label)) {
            // the variable does not occur (scenario D: a CPR overwrote the attribute): whole domain
            return new Mode2Result.RealInterval(s.minReal, false, s.maxReal, false);
        }
        if (Exprs.nonlinear(pc)) {
            // Optimize is unreliable or hangs on nonlinear conditions (scenario D, 2026-11-09)
            double tol = (s.maxReal - s.minReal) * Double.parseDouble(System.getProperty("mode2.bisectTolerance", "1e-5"));
            Mode2Result.RealInterval iv = new Mode2Result.RealInterval(
                    bisect(pc, s, true, tol), false, bisect(pc, s, false, tol), false);
            iv.approximate = true;
            return iv;
        }
        // Optimize on the closure (Exprs.closure: Z3 4.8.14 gets strict real suprema wrong), then
        // verify on the original condition and decide each end's openness there.
        Mode2Solver.Result b = solver.bounds(Exprs.closure(pc), s.label);
        if (b != null && b.sat() && b.model.get("min") != null && b.model.get("max") != null) {
            double lo = Double.parseDouble(b.model.get("min").replace("?", "")); // "?" marks a truncated decimal
            double hi = Double.parseDouble(b.model.get("max").replace("?", ""));
            boolean tight = !sat(and(pc, cmp(s, Operator.LT, lo))) && !sat(and(pc, cmp(s, Operator.GT, hi)));
            if (tight) {
                return new Mode2Result.RealInterval(lo, !sat(and(pc, cmp(s, Operator.EQ, lo))),
                        hi, !sat(and(pc, cmp(s, Operator.EQ, hi))));
            }
            System.out.println("[mode2] optimizer bounds not tight for " + s.label + ", bisection");
        } else {
            System.out.println("[mode2] optimizer bounds failed (" + (b == null ? "null" : b.status) + "), bisection for " + s.label);
        }
        double tol = (s.maxReal - s.minReal) * Double.parseDouble(System.getProperty("mode2.bisectTolerance", "1e-5"));
        Mode2Result.RealInterval iv = new Mode2Result.RealInterval(
                bisect(pc, s, true, tol), false, bisect(pc, s, false, tol), false);
        iv.approximate = true;
        return iv;
    }

    /**
     * Smallest (lower) or largest real value of s within pc, to tolerance tol. Each step has its
     * own time limit ({@code mode2.bisectTimeoutMs}, default 2 s); an UNKNOWN step keeps the outer
     * bound, so the extent is over-approximated, never cut (the interval is marked approximate).
     */
    private double bisect(Expression pc, UncertaintySource s, boolean lower, double tol) throws Exception {
        int limit = Integer.getInteger("mode2.bisectTimeoutMs", 2000);
        double a = s.minReal;
        double b = s.maxReal;
        while (b - a > tol) {
            double m = (a + b) / 2;
            if (lower) {
                if (!"UNSATISFIABLE".equals(solver.check(and(pc, cmp(s, Operator.LE, m)), limit).status)) {
                    b = m;
                } else {
                    a = m;
                }
            } else {
                if (!"UNSATISFIABLE".equals(solver.check(and(pc, cmp(s, Operator.GE, m)), limit).status)) {
                    a = m;
                } else {
                    b = m;
                }
            }
        }
        return lower ? a : b;
    }

    private static Expression realBox(UncertaintySource s, Mode2Result.RealInterval iv) {
        return and(cmp(s, iv.loOpen ? Operator.GT : Operator.GE, iv.lo), cmp(s, iv.hiOpen ? Operator.LT : Operator.LE, iv.hi));
    }

    /**
     * Exact description of a predicate region over integer and real variables: for every
     * combination of integer values in the region's hull that the path condition admits, the
     * real intervals. Exact if each slice is the product of its intervals (checked); then the
     * region's probability is the sum over slices of their real shares, weighted by the integer
     * combinations' share. Skipped above 2000 combinations.
     */
    private void slices(Mode2Result.Region r, Expression pc, PathRun p) throws Exception {
        // Slice only over integer variables that share a branch constraint with a real variable
        // (scenario C: the diameter, not the centering diameter, which only has a "!= 0" check);
        // the exactness check below covers the others over their whole domain.
        Set<String> realLabels = new HashSet<>();
        sources.stream().filter(x -> x.real).forEach(x -> realLabels.add(x.label));
        Set<String> coupled = new HashSet<>();
        for (Expression b : p.branches) {
            Set<String> vars = Exprs.variables(b);
            if (vars.stream().anyMatch(realLabels::contains)) {
                coupled.addAll(vars);
            }
        }
        List<UncertaintySource> ints = sources.stream().filter(x -> !x.real && coupled.contains(x.label)).toList();
        long combinations = 1;
        for (UncertaintySource s : ints) {
            combinations *= r.intervals.get(s.label)[1] - r.intervals.get(s.label)[0] + 1;
        }
        if (combinations > 2000) {
            return;
        }
        double intDomain = 1;
        for (UncertaintySource s : ints) {
            intDomain *= s.max - s.min + 1;
        }
        double probability = 0;
        boolean exact = true;
        int[] at = new int[ints.size()];
        for (int k = 0; k < ints.size(); k++) {
            at[k] = r.intervals.get(ints.get(k).label)[0];
        }
        for (long c = 0; c < combinations; c++) {
            Expression fixedPc = pc;
            Mode2Result.Slice sl = new Mode2Result.Slice();
            for (int k = 0; k < ints.size(); k++) {
                fixedPc = and(fixedPc, new BinaryOperation(Operator.EQ, var(ints.get(k)), new IntConstant(at[k])));
                sl.at.put(ints.get(k).label, at[k]);
            }
            if (sat(fixedPc)) {
                Expression box = null;
                double share = 1;
                for (UncertaintySource s : sources) {
                    if (s.real) {
                        Mode2Result.RealInterval iv = realBounds(fixedPc, s);
                        sl.real.put(s.label, iv);
                        box = and(box, realBox(s, iv));
                        share *= iv.length() / (s.maxReal - s.minReal);
                        exact &= !iv.approximate;
                    }
                }
                Expression fixedDomain = domain();
                for (int k = 0; k < ints.size(); k++) {
                    fixedDomain = and(fixedDomain, new BinaryOperation(Operator.EQ, var(ints.get(k)), new IntConstant(at[k])));
                }
                exact &= !sat(and(and(fixedDomain, box), not(pc))); // the slice is exactly its intervals
                probability += share / intDomain;
                r.slices.add(sl);
            }
            for (int k = ints.size() - 1; k >= 0; k--) { // next combination
                if (++at[k] <= r.intervals.get(ints.get(k).label)[1]) {
                    break;
                }
                at[k] = r.intervals.get(ints.get(k).label)[0];
            }
        }
        if (exact) {
            r.probability = probability;
        } else {
            r.slices.clear(); // not exact: the reported intervals would claim more than they are
        }
    }

    /**
     * Evaluates the nominal state on the unchanged model and compares it with the region that
     * contains the nominal point. Different verdicts mean the nominal state is not a fixpoint of
     * the CPRs; the nominal point then becomes a region of its own.
     */
    private void nominalCheck(List<PathRun> paths) throws Exception {
        Map<String, Number> nominal = new LinkedHashMap<>();
        for (UncertaintySource s : sources) {
            nominal.put(s.label, s.nominalValue());
        }
        Map<String, Boolean> verdicts = new LinkedHashMap<>();
        try (Mode2Backend.Session session = backend.checkout()) {
            if (!config.incomingChanges.isEmpty()) {
                // nominal realization plus the incoming change
                apply(session, config.incomingChanges);
                session.commit();
            }
            Collection<Resource> resources = sources.isEmpty() ? List.of() : session.resources();
            verdicts.putAll(session.evaluate(resources, nominalTiming()));
            nominalStructure = structure(resources);
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
                from.extentType = "predicate"; // a box minus one point
                if (from.probability != null) {
                    from.probability -= nominalPointProbability();
                }
            }
            Mode2Result.Region point = new Mode2Result.Region();
            point.extentType = "box";
            for (UncertaintySource s : sources) {
                if (s.real) {
                    point.realIntervals.put(s.label, new Mode2Result.RealInterval(s.nominalReal, false, s.nominalReal, false));
                } else {
                    point.intervals.put(s.label, new int[] {s.nominal, s.nominal});
                }
            }
            point.representative.putAll(nominal);
            point.verdicts.putAll(verdicts);
            point.consistent = n.consistent;
            point.pathCondition.add("nominal state (unchanged model)");
            point.probability = nominalPointProbability();
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

    private boolean covered(List<PathRun> paths, Map<String, Number> input) throws Exception {
        Expression point = point(input);
        for (PathRun p : paths) {
            if (sat(and(and(domain(), pc(p)), point))) {
                return true;
            }
        }
        return false;
    }

    /** A domain point outside all explored regions, or null if the regions cover the domain. */
    private Map<String, Number> uncoveredPoint(List<PathRun> paths) throws Exception {
        Expression outside = domain();
        for (PathRun p : paths) {
            outside = and(outside, not(pc(p)));
        }
        Map<String, Number> m = solve(and(outside, preferNonNominal()));
        return m != null ? m : solve(outside);
    }

    private Map<String, Number> initialInput() {
        Map<String, Number> in = new LinkedHashMap<>();
        for (UncertaintySource s : sources) {
            if (s.real) {
                in.put(s.label, s.nominalReal != s.minReal ? s.minReal : s.maxReal);
            } else {
                in.put(s.label, s.nominal != s.min ? s.min : s.max);
            }
        }
        return in;
    }

    private Map<String, Number> solve(Expression q) throws Exception {
        Mode2Solver.Result r = solver.check(q);
        if (!r.sat()) {
            return null;
        }
        Map<String, Number> in = new LinkedHashMap<>();
        for (UncertaintySource s : sources) {
            String v = r.model.get(s.label);
            if (v == null) {
                in.put(s.label, s.nominalValue());
            } else if (s.real) {
                in.put(s.label, Double.parseDouble(v.replace("?", ""))); // RatNum.toDecimalString marks truncation
            } else {
                in.put(s.label, Integer.parseInt(v));
            }
        }
        return in;
    }

    /** Regions grouped by identical verdicts, in order of first occurrence (for display). */
    private void verdictClasses() {
        Map<Map<String, Boolean>, Mode2Result.VerdictClass> classes = new LinkedHashMap<>();
        for (int i = 0; i < result.regions.size(); i++) {
            Mode2Result.Region r = result.regions.get(i);
            Mode2Result.VerdictClass c = classes.computeIfAbsent(new LinkedHashMap<>(r.verdicts), v -> {
                Mode2Result.VerdictClass n = new Mode2Result.VerdictClass();
                n.verdicts.putAll(v);
                n.consistent = r.consistent;
                return n;
            });
            c.regions.add(i);
            if (c.probability != null || c.regions.size() == 1) {
                c.probability = r.probability == null ? null : (c.probability == null ? 0 : c.probability) + r.probability;
            }
        }
        result.verdictClasses.addAll(classes.values());
    }

    /** Region of every requested point (see {@link Options#points}); -1 outside the annotated domain. */
    private void classify(List<PathRun> paths) throws Exception {
        Expression dom = domain();
        Map<String, Number> nominal = new LinkedHashMap<>();
        for (UncertaintySource s : sources) {
            nominal.put(s.label, s.nominalValue());
        }
        for (Map<String, Number> requested : config.points) {
            Map<String, Number> full = new LinkedHashMap<>();
            for (UncertaintySource s : sources) {
                Number v = requested.get(s.label);
                full.put(s.label, v == null ? s.nominalValue() : v);
            }
            Mode2Result.PointRegion pr = new Mode2Result.PointRegion();
            pr.values.putAll(full);
            for (String k : requested.keySet()) {
                if (!full.containsKey(k)) {
                    pr.ignored.add(k);
                }
            }
            if (!result.nominal.fixpointConfirmed && full.equals(nominal)) {
                pr.region = result.nominal.region;
            } else if (sat(and(dom, point(full)))) {
                for (int i = 0; i < paths.size(); i++) {
                    if (sat(and(and(dom, pc(paths.get(i))), point(full)))) {
                        pr.region = i;
                        break;
                    }
                }
                pr.unexplored = pr.region < 0; // in the domain, but the exploration stopped before reaching it
            }
            pr.consistent = pr.region >= 0 && result.regions.get(pr.region).consistent;
            result.points.add(pr);
        }
    }

    private boolean sat(Expression e) throws Exception {
        return solver.check(e).sat();
    }

    private Expression domain() {
        Expression d = null;
        for (UncertaintySource s : sources) {
            d = s.real ? and(d, and(ge(s, s.minReal), le(s, s.maxReal))) : and(d, and(ge(s, s.min), le(s, s.max)));
        }
        return d == null ? new BinaryOperation(Operator.EQ, new IntConstant(0), new IntConstant(0)) : d;
    }

    private Expression preferNonNominal() {
        Expression d = null;
        for (UncertaintySource s : sources) {
            if (s.hasAlternatives()) {
                d = and(d, new BinaryOperation(Operator.NE, var(s), constant(s, s.nominalValue())));
            }
        }
        return d;
    }

    private Expression point(Map<String, Number> input) {
        Expression d = null;
        for (UncertaintySource s : sources) {
            d = and(d, new BinaryOperation(Operator.EQ, var(s), constant(s, input.get(s.label))));
        }
        return d;
    }

    private static Expression pc(PathRun p) {
        return conj(p.branches, p.branches.size());
    }

    /** Bounded integer variable (Z3 Int with the annotation's domain), as Knarr's symbolicInRange creates it. */
    private static Expression var(UncertaintySource s) {
        return s.real ? new RealVariable(s.label, s.minReal, s.maxReal) : new IntVariable(s.label, s.min, s.max);
    }

    private static Expression constant(UncertaintySource s, Number v) {
        return s.real ? new RealConstant(v.doubleValue()) : new IntConstant(v.intValue());
    }

    /** Tag expression of a real source's member: {@code nominal_i + δ} for a shift, else the variable. */
    private static Expression memberExpression(UncertaintySource s, UncertaintySource.Member m) {
        return s.offset ? new BinaryOperation(Operator.ADD, new RealConstant(m.nominal), var(s)) : var(s);
    }

    private static Expression ge(UncertaintySource s, Number v) {
        return new BinaryOperation(Operator.GE, var(s), constant(s, v));
    }

    private static Expression cmp(UncertaintySource s, Operator op, double v) {
        return new BinaryOperation(op, var(s), constant(s, v));
    }

    private static Expression le(UncertaintySource s, Number v) {
        return new BinaryOperation(Operator.LE, var(s), constant(s, v));
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
}
