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
    }
}
