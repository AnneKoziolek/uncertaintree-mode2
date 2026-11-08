package uncertaintree.mode2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one Mode-2 analysis; public fields so that it serializes to JSON as is.
 * This is the contract towards a demonstrator UI (decided 2026-11-08: the UI is built by
 * the demonstrator team).
 */
public final class Mode2Result {
    public String scenario;
    public List<Source> sources = new ArrayList<>();
    /** Uncertainties that Mode 2 does not treat symbolically (kept at their nominal value). */
    public List<String> unsupported = new ArrayList<>();
    public List<Region> regions = new ArrayList<>();
    public Nominal nominal = new Nominal();
    /**
     * How Region#probability is computed. The uncertainty metamodel has no distribution for an
     * integer range, so every integer point of a source's domain is taken as equally likely and the
     * sources as independent (the same weighting as counting Mode 1's states).
     */
    public String probabilityModel = "uniform over each source's integer domain, sources independent";
    /**
     * Elements ("EClass handle") whose number differs between the regions' committed models:
     * candidates for existence annotations (architecture doc, UCM10 step 2). Empty when all
     * regions have the same elements, even if they differ from the nominal state (see
     * Region#elementsAdded).
     */
    public List<String> elementsVaryingBetweenRegions = new ArrayList<>();
    /** Per annotated attribute (symbolic or not): what the rules did with it on the explored paths. */
    public List<AnnotationCheck> annotationChecks = new ArrayList<>();
    public Stats stats = new Stats();

    /**
     * The nominal state, evaluated on the unchanged model (no propagation: setting the current
     * value is no change for Vitruvius). Its verdicts equal those of the region containing the
     * nominal point iff the nominal state is a fixpoint of the CPRs (see docs/mode2-concepts.md).
     */
    public static final class Nominal {
        public Map<String, Integer> values = new LinkedHashMap<>();
        public Map<String, Boolean> verdicts = new LinkedHashMap<>();
        public boolean consistent;
        /** True if the region containing the nominal point has the same verdicts. */
        public boolean fixpointConfirmed;
        /** Index into regions of the region that contains the nominal point (after a split: the point region). */
        public int region = -1;
        /**
         * True if the committed models of the region containing the nominal point have other
         * elements than the unchanged nominal model: propagation of the first change creates or
         * deletes elements, so the nominal state is not a structural fixpoint of the CPRs.
         */
        public boolean structureDiffers;
    }

    public static final class Source {
        public String variable;
        public String uncertainty;
        public String element;
        public String attribute;
        public int min;
        public int max;
        public int nominal;
    }

    /** One explored path: a region of the configuration space with one verdict per constraint. */
    public static final class Region {
        /** "box" if the path condition is exactly the product of the intervals, else "predicate". */
        public String extentType;
        /** Per variable: [lo, hi] (exact for "box", the bounding hull for "predicate"). */
        public Map<String, int[]> intervals = new LinkedHashMap<>();
        /** The path condition (depth-limited rendering). */
        public List<String> pathCondition = new ArrayList<>();
        public boolean consistent;
        public Map<String, Boolean> verdicts = new LinkedHashMap<>();
        /** Model attributes whose value depends on uncertain variables, with their expression. */
        public List<Affected> affected = new ArrayList<>();
        public Map<String, Integer> representative = new LinkedHashMap<>();
        /** True if an operation on a symbolic value overflowed on this path (OverflowMonitor). */
        public boolean approximate;
        public String approximateReason;
        /** Set when the nominal point was split off this region (fixpoint check failed). */
        public boolean excludesNominal;
        /**
         * Probability mass of the region under probabilityModel: its integer points over all
         * points of the domain. Exact for "box" regions (also after the nominal point was split
         * off); null for "predicate" regions, whose points are not counted.
         */
        public Double probability;
        /**
         * Elements of the committed models that exist on this region's path and not in the
         * nominal state, and the other way round, as "EClass handle" (multiset difference;
         * elements without an id attribute are keyed by EClass only). Empty when the rules create
         * and delete the same elements everywhere; then the regions differ only in attribute values.
         */
        public List<String> elementsAdded = new ArrayList<>();
        public List<String> elementsRemoved = new ArrayList<>();
    }

    /**
     * What happened to one annotated attribute after propagation, on the paths where its source
     * was realized at a non-nominal value (symbolic sources) or on all paths (others).
     * status: "independent" (still carries exactly its own variable), "rewritten" (an expression
     * over its own variable only, e.g. a round trip through another model; see identityOnDomain), "derived" (an expression over other sources' variables: the
     * rules compute it from other roots, so it is a dependent annotation, concepts 16),
     * "resolved" (concrete after propagation: a rule or the incoming change overwrote it),
     * "untouched" (a non-symbolic annotation that no source reaches).
     */
    public static final class AnnotationCheck {
        public String uncertainty;
        public String element;
        public String attribute;
        /** True if Mode 2 treats this annotation as a symbolic source. */
        public boolean symbolic;
        public String status;
        /** Variables its value depends on after propagation (union over paths). */
        public java.util.Set<String> dependsOn = new java.util.TreeSet<>();
        /** One expression seen (depth-limited), for "rewritten" and "derived". */
        public String expression;
        /**
         * For "rewritten": true if the solver proves the expression equal to the variable on the
         * explored paths' parts of the domain (the rules write the value back unchanged), false if
         * not, null if not checked.
         */
        public Boolean identityOnDomain;
        /** Number of paths per observed status. */
        public Map<String, Integer> paths = new LinkedHashMap<>();
    }

    public static final class Affected {
        public String element;
        public String attribute;
        public String expression;
    }

    public static final class Stats {
        public int concreteExecutions;
        /** Executed inputs whose path differed from the path predicted by the solver. */
        public int divergences;
        public int solverQueries;
        public long solverMillis;
        public long totalMillis;
        public List<Long> pathMillis = new ArrayList<>();
        /** Accumulated time per phase (source discovery, solver start, checkout, commit, OCL per invariant, tag scan, nominal check). */
        public Map<String, Long> phaseMillis = new LinkedHashMap<>();
    }
}
