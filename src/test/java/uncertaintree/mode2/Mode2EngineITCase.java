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

    /**
     * Steps 2/4 of the demo plan: a change comes in on the uncertain model (ventilated := false).
     * It is propagated after the realization of d in the same commit, so the CPRs and the
     * evaluator see the tagged d. Expected from the constraints: ventilated holds everywhere
     * (the implication's conclusion is now true); the caliper check still splits at 330.
     */
    @Test
    void scenarioAWithIncomingChange() throws Exception {
        Mode2Engine.Config c = new Mode2Engine.Config();
        c.scenario = "A+ventilated=false";
        c.vsumState = Scenarios.prepare("A", "incoming");
        c.cps = Scenarios.cps();
        c.rootTypes = Scenarios.ROOT_TYPES;
        c.oclFile = Scenarios.oclFile();
        c.solverPort = Integer.getInteger("mode2.solverPort", 9411) + 5;
        c.incomingChanges.add(new Mode2Engine.AttributeChange(
                "http://vitruv.tools/brakesystem/model::BrakeDisk::id3_front_brake_disc", "ventilated", "false"));
        Mode2Result r = new Mode2Engine(c).analyze();
        JSON.writeValue(Path.of("target", "mode2-result-A-incoming.json").toFile(), r);
        for (Mode2Result.Region g : r.regions) {
            System.out.println("[mode2-result] incoming: " + g.extentType + " " + g.intervals.values().stream()
                    .map(iv -> "[" + iv[0] + ", " + iv[1] + "]").toList() + " " + g.verdicts);
        }
        System.out.println("[mode2-result] incoming: nominal " + r.nominal.verdicts + ", fixpoint confirmed "
                + r.nominal.fixpointConfirmed + ", executions " + r.stats.concreteExecutions);
        for (int d = 325; d <= 337; d++) {
            final int dd = d;
            List<Mode2Result.Region> covering = r.regions.stream().filter(g -> {
                int[] iv = g.intervals.values().iterator().next();
                return iv[0] <= dd && dd <= iv[1];
            }).toList();
            Assertions.assertEquals(1, covering.size(), "one region per point, d=" + d);
            Mode2Result.Region g = covering.get(0);
            Assertions.assertTrue(g.verdicts.get("ventilated"), "ventilated holds after the change, d=" + d);
            Assertions.assertEquals(d <= 329, g.consistent, "caliper boundary unchanged, d=" + d);
        }
        Assertions.assertTrue(r.nominal.fixpointConfirmed);
    }

    /**
     * Dependency check (concepts 16): scenario D also annotates the CAD cylinder radius, which
     * brakesystem2cad computes from the diameter. The radius (EFloat, not symbolic) must be
     * reported as derived from the diameter's variable, the diameter as independent; the regions
     * are those of scenario A, since the radius annotation is kept nominal and overwritten.
     */
    @Test
    void dependentAnnotationIsDetected() throws Exception {
        Mode2Engine.Config c = new Mode2Engine.Config();
        c.scenario = "D";
        c.vsumState = Scenarios.prepare("D", "mode2");
        c.cps = Scenarios.cps();
        c.rootTypes = Scenarios.ROOT_TYPES;
        c.oclFile = Scenarios.oclFile();
        c.solverPort = Integer.getInteger("mode2.solverPort", 9411) + 7;
        Mode2Result r = new Mode2Engine(c).analyze();
        JSON.writeValue(Path.of("target", "mode2-result-D.json").toFile(), r);
        String d = r.sources.get(0).variable;
        Map<String, Mode2Result.AnnotationCheck> byAttr = new java.util.HashMap<>();
        for (Mode2Result.AnnotationCheck a : r.annotationChecks) {
            System.out.println("[mode2-result] D annotation " + a.uncertainty + " " + a.attribute + ": " + a.status
                    + " " + a.dependsOn + " " + a.paths + (a.expression == null ? "" : " " + a.expression));
            byAttr.put(a.attribute, a);
        }
        Assertions.assertEquals(1, r.sources.size(), "only the diameter is symbolic");
        Assertions.assertEquals("rewritten", byAttr.get("diameterInMM").status, "round trip through the CAD parameter");
        Assertions.assertEquals(Boolean.TRUE, byAttr.get("diameterInMM").identityOnDomain);
        Assertions.assertEquals("derived", byAttr.get("radius").status);
        Assertions.assertEquals(java.util.Set.of(d), byAttr.get("radius").dependsOn);
        Assertions.assertEquals(3, r.regions.size());
    }

    /**
     * Dependency check: an incoming change that sets the annotated diameter itself to a concrete
     * value decides the uncertainty. The diameter is reported as resolved, and the whole domain
     * is one region with the verdicts of d = 333 (caliper check fails), probability 1.
     */
    @Test
    void incomingChangeResolvesTheUncertainty() throws Exception {
        Mode2Engine.Config c = new Mode2Engine.Config();
        c.scenario = "A+diameter=333";
        c.vsumState = Scenarios.prepare("A", "resolve");
        c.cps = Scenarios.cps();
        c.rootTypes = Scenarios.ROOT_TYPES;
        c.oclFile = Scenarios.oclFile();
        c.solverPort = Integer.getInteger("mode2.solverPort", 9411) + 8;
        c.incomingChanges.add(new Mode2Engine.AttributeChange(
                "http://vitruv.tools/brakesystem/model::BrakeDisk::id3_front_brake_disc", "diameterInMM", "333"));
        Mode2Result r = new Mode2Engine(c).analyze();
        JSON.writeValue(Path.of("target", "mode2-result-A-resolve.json").toFile(), r);
        for (Mode2Result.AnnotationCheck a : r.annotationChecks) {
            System.out.println("[mode2-result] resolve annotation " + a.uncertainty + ": " + a.status + " " + a.paths);
        }
        for (Mode2Result.Region g : r.regions) {
            System.out.println("[mode2-result] resolve region " + g.extentType + " " + g.intervals.values().stream()
                    .map(iv -> "[" + iv[0] + ", " + iv[1] + "]").toList() + " " + g.verdicts + " p=" + g.probability);
        }
        Assertions.assertEquals("resolved", r.annotationChecks.get(0).status);
        Assertions.assertEquals(1, r.regions.size(), "the change makes every input take the same path");
        Assertions.assertEquals(1.0, r.regions.get(0).probability, 1e-9);
        Assertions.assertFalse(r.regions.get(0).consistent, "d = 333 violates the caliper check");
    }

    @SuppressWarnings("unchecked")
    static Mode2Result run(String scenario) throws Exception {
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
        Assertions.assertEquals(compared * r.regions.get(0).verdicts.size(), constraintChecks,
                "every constraint of every Mode-1 state compared");
        System.out.println("[mode2-result] agreement with Mode 1: " + compared + " states (Mode 1 took "
                + baseline.get("millis") + " ms), " + constraintChecks + " per-constraint checks");

        // Uniform region probabilities equal the share of Mode 1's states in each region
        // (Mode 1 enumerates every integer point of the domain once).
        double sum = 0;
        for (Mode2Result.Region g : r.regions) {
            Assertions.assertNotNull(g.probability, "box regions have a probability");
            long inRegion = rows.stream().filter(row -> {
                Map<String, Object> values = (Map<String, Object>) row.get("values");
                boolean inside = true;
                for (UncertaintySource s : engine.sources()) {
                    Object v = values.get(s.handle + "#" + s.attribute);
                    int x = v == null ? s.nominal : ((Number) v).intValue();
                    int[] iv = g.intervals.get(s.label);
                    inside &= iv[0] <= x && x <= iv[1];
                }
                return inside;
            }).count();
            Assertions.assertEquals((double) inRegion / rows.size(), g.probability, 1e-9, "probability of " + g.intervals.keySet());
            sum += g.probability;
            System.out.println("[mode2-result] p=" + g.probability + " " + g.intervals.keySet() + ", elements added "
                    + g.elementsAdded + ", removed " + g.elementsRemoved + " (against the unchanged nominal model)");
            // The first propagation creates the CAD parameter for the diameter, on every path alike.
            Assertions.assertEquals(r.regions.get(0).elementsAdded, g.elementsAdded);
            Assertions.assertEquals(r.regions.get(0).elementsRemoved, g.elementsRemoved);
        }
        Assertions.assertEquals(1.0, sum, 1e-9, "probabilities sum to 1");
        // The brake rules create and delete the same elements in every region.
        Assertions.assertEquals(List.of(), r.elementsVaryingBetweenRegions);
        System.out.println("[mode2-result] nominal state structurally different from the propagated states: "
                + r.nominal.structureDiffers);
        // Each symbolic source keeps its value: unchanged, or written back unchanged by a round
        // trip (diameter -> CAD parameter -> diameter = round(value)).
        for (Mode2Result.AnnotationCheck a : r.annotationChecks) {
            System.out.println("[mode2-result] annotation " + a.uncertainty + " " + a.attribute + ": " + a.status
                    + " " + a.dependsOn + " " + a.paths + (a.expression == null ? "" : " " + a.expression)
                    + (a.identityOnDomain == null ? "" : ", identity on domain " + a.identityOnDomain));
            Assertions.assertTrue("independent".equals(a.status)
                    || "rewritten".equals(a.status) && Boolean.TRUE.equals(a.identityOnDomain), a.uncertainty + ": " + a.status);
        }
        return r;
    }
}
