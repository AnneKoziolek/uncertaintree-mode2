package uncertaintree.mode2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Mode 2 through the generic {@link Mode2Engine} on scenarios A and B, compared point by
 * point with the real Mode 1 (target/mode1-baseline-*.json from Mode1BaselineTest, which
 * runs first in the plain JVM). The test knows only Mode 1's inputs.
 */
public class Mode2EngineITCase {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @BeforeAll
    static void packages() {
        Scenarios.registerPackages();
    }

    @Test
    void scenarioA() throws Exception {
        run("A");
    }

    @Test
    void scenarioB() throws Exception {
        run("B");
    }

    @SuppressWarnings("unchecked")
    static void run(String scenario) throws Exception {
        Mode2Engine.Config c = new Mode2Engine.Config();
        c.scenario = scenario;
        c.vsumState = Scenarios.prepare(scenario, "mode2");
        c.cps = Scenarios.cps();
        c.rootTypes = Scenarios.ROOT_TYPES;
        c.oclFile = Scenarios.oclFile();
        c.solverPort = Integer.getInteger("mode2.solverPort", 9411) + scenario.charAt(0) - 'A';
        Mode2Engine engine = new Mode2Engine(c);
        Mode2Result r = engine.analyze();
        Path out = Path.of("target", "mode2-result-" + scenario + ".json").toAbsolutePath();
        JSON.writeValue(out.toFile(), r);

        System.out.println("[mode2-result] ==== scenario " + scenario + " ====");
        r.sources.forEach(s -> System.out.println("[mode2-result] source " + s.variable + " = " + s.element + "."
                + s.attribute + " in [" + s.min + ", " + s.max + "], nominal " + s.nominal));
        r.unsupported.forEach(u -> System.out.println("[mode2-result] unsupported: " + u));
        for (Mode2Result.Region g : r.regions) {
            StringBuilder iv = new StringBuilder();
            g.intervals.forEach((k, v) -> iv.append(k).append(" in [").append(v[0]).append(", ").append(v[1]).append("] "));
            System.out.println("[mode2-result] " + g.extentType + " " + iv + ": " + (g.consistent ? "CONSISTENT" : "INCONSISTENT")
                    + " " + g.verdicts + ", affected " + g.affected.size());
        }
        System.out.println("[mode2-result] nominal " + r.nominal.values + ": " + (r.nominal.consistent ? "CONSISTENT" : "INCONSISTENT")
                + ", fixpoint confirmed: " + r.nominal.fixpointConfirmed);
        Assertions.assertTrue(r.nominal.fixpointConfirmed, "nominal state of the brake example is a CPR fixpoint");
        Assertions.assertTrue(r.regions.stream().noneMatch(g -> g.approximate), "no overflow expected in the brake example");
        System.out.printf("[mode2-result] executions %d, divergences %d, solver %d queries in %d ms, total %d ms%n",
                r.stats.concreteExecutions, r.stats.divergences, r.stats.solverQueries, r.stats.solverMillis,
                r.stats.totalMillis);
        System.out.println("[mode2-result] JSON: " + out);

        // Point-by-point agreement with the real Mode 1.
        Path baselineFile = Scenarios.baselineFile(scenario);
        Assertions.assertTrue(Files.exists(baselineFile), "Mode-1 baseline missing: run Mode1BaselineTest first");
        Map<String, Object> baseline = JSON.readValue(baselineFile.toFile(), Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) baseline.get("rows");
        int compared = 0;
        int constraintChecks = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> values = (Map<String, Object>) row.get("values");
            List<Mode2Result.Region> covering = new ArrayList<>();
            for (Mode2Result.Region g : r.regions) {
                Assertions.assertEquals("box", g.extentType, "comparison needs exact box extents");
                boolean inside = true;
                for (UncertaintySource s : engine.sources()) {
                    Object v = values.get(s.handle + "#" + s.attribute);
                    int x = v == null ? s.nominal : ((Number) v).intValue();
                    int[] iv = g.intervals.get(s.label);
                    inside &= iv[0] <= x && x <= iv[1];
                }
                if (inside) {
                    covering.add(g);
                }
            }
            Assertions.assertEquals(1, covering.size(), "exactly one region per Mode-1 state " + values);
            Mode2Result.Region g = covering.get(0);
            Assertions.assertEquals(row.get("consistent"), g.consistent, "global verdict at " + values);
            Map<String, Boolean> m1 = (Map<String, Boolean>) row.get("constraints");
            for (Map.Entry<String, Boolean> e : m1.entrySet()) {
                if (g.verdicts.containsKey(e.getKey())) {
                    Assertions.assertEquals(e.getValue(), g.verdicts.get(e.getKey()), e.getKey() + " at " + values);
                    constraintChecks++;
                }
            }
            compared++;
        }
        System.out.println("[mode2-result] agreement with Mode 1: " + compared + " states (Mode 1 took "
                + baseline.get("millis") + " ms), " + constraintChecks + " per-constraint checks");
    }
}
