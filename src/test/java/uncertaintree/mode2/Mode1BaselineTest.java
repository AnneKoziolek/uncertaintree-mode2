package uncertaintree.mode2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.vsum.VirtualModel;
import uncertainty.UncertaintyAnnotationRepository;
import uncertaintree.ocl.CliOclEvaluator;
import uncertaintree.ocl.OclConstraintResult;
import uncertaintree.ocl.OclEvaluationResult;
import uncertaintree.stateextraction.DefaultEObjectStateSpaceExplorer;
import uncertaintree.stateextraction.UncertaintyDimensionResolver;
import uncertaintree.utils.VsumUtils;
import uncertaintree.vsum.DefaultUncertainEObjectSelector;
import uncertaintree.vsum.EObjectStateChoice;
import uncertaintree.vsum.IdAttributeHandleStrategy;
import uncertaintree.vsum.VSUMState;
import uncertaintree.vsum.VSUMStateExplorer;
import uncertaintree.vsum.execution.CopyingVitruvViewProvider;
import uncertaintree.vsum.execution.IdBasedVsumObjectResolver;
import uncertaintree.vsum.execution.VitruvTestViewDefinition;
import uncertaintree.vsum.execution.VsumStateExecutor;

/**
 * Mode-1 baseline (plain JVM, surefire): UncertainTree's real Mode 1, called as in
 * UncertainTreeDev's CPS1 test (state explorer, isolated execution, CLI OCL evaluator),
 * writes every explored state with its verdicts to target/mode1-baseline-&lt;scenario&gt;.json.
 * Mode2EngineITCase compares Mode 2's regions against it point by point.
 */
class Mode1BaselineTest {
    static final Path ECORE_DIR = Path.of(System.getProperty("ut.ecoreDir",
            "/workspace/UncertainTreeDev/BrakeCaseStudy/Maven_Project/model/src/main/ecore"));

    @BeforeAll
    static void packages() {
        Scenarios.registerPackages();
    }

    @Test
    void scenarioA() throws Exception {
        baseline("A");
    }

    @Test
    void scenarioB() throws Exception {
        baseline("B");
    }

    /** Scenario C: UncertainTreeDev's CPS3 (1008 states, grouped real-valued caliper shift). */
    @Test
    void scenarioC() throws Exception {
        baseline("C");
    }

    static void baseline(String scenario) throws Exception {
        Path vsumState = Scenarios.prepare(scenario, "mode1");
        VsumUtils util = new VsumUtils();
        VirtualModel vsum = util.createDefaultVirtualModel(vsumState, Scenarios.cps());
        View view = util.getDefaultView(vsum, Scenarios.ROOT_TYPES);
        UncertaintyAnnotationRepository repo =
                view.getRootObjects(UncertaintyAnnotationRepository.class).iterator().next();
        List<org.eclipse.emf.ecore.EObject> roots = new ArrayList<>();
        roots.addAll(view.getRootObjects(brakesystem.Brakesystem.class));
        roots.addAll(view.getRootObjects(cad.CAD_Model.class));
        List<VSUMState> states = new VSUMStateExplorer(new DefaultEObjectStateSpaceExplorer(),
                new DefaultUncertainEObjectSelector(), new IdAttributeHandleStrategy()).exploreStates(repo, roots);

        Path results = Path.of("target", "mode1-results-" + scenario).toAbsolutePath();
        Files.createDirectories(results);
        CliOclEvaluator ocl = new CliOclEvaluator(Scenarios.oclFile(scenario), results);
        VsumStateExecutor<VitruvTestViewDefinition> executor =
                new VsumStateExecutor<>(new CopyingVitruvViewProvider(util, new IdBasedVsumObjectResolver()));
        VitruvTestViewDefinition def = new VitruvTestViewDefinition(vsumState, Scenarios.cps(), Scenarios.ROOT_TYPES);
        List<Path> ecores = List.of(ECORE_DIR.resolve("cad.ecore"), ECORE_DIR.resolve("brakesystem.ecore"));

        // Same names as Mode 2 (declared name, or inv<position> for an unnamed invariant).
        List<String> names = new ArrayList<>();
        List<String> texts = Mode2Engine.invariants(Scenarios.oclFile(scenario));
        for (int k = 0; k < texts.size(); k++) {
            names.add(OclPipeline.nameOf(texts.get(k), k));
        }
        long t0 = System.nanoTime();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            Scenarios.registerPackages();
            VSUMState state = states.get(i);
            final int idx = i;
            OclEvaluationResult r = executor.applyCommitAndEvaluate(def, state, (session, applied) ->
                    ocl.evaluate(applied, ecores, session.getConcreteModelFiles(), idx, new String[] {".cad", ".brakesystem"}));
            Map<String, Object> values = new LinkedHashMap<>();
            for (EObjectStateChoice c : state.getChoices()) {
                for (Map.Entry<String, Object> e : c.getEObjectState().getAttributeValues().entrySet()) {
                    if (!UncertaintyDimensionResolver.isExistenceActionAttributeName(e.getKey())) {
                        values.put(c.getObjectHandle() + "#" + e.getKey(), e.getValue());
                    }
                }
            }
            // The CLI evaluator names every constraint "unknown"; its results come in the order of
            // the invariants in the OCL file (checked 2026-11-08 against its violation messages).
            Map<String, Boolean> constraints = new LinkedHashMap<>();
            List<OclConstraintResult> cs = r.getConstraints();
            for (int k = 0; k < cs.size(); k++) {
                String name = cs.get(k).getName();
                if ((name == null || "unknown".equals(name)) && cs.size() == names.size()) {
                    name = names.get(k);
                }
                constraints.put(name, cs.get(k).isSatisfied());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("values", values);
            row.put("consistent", r.isGloballyConsistent());
            row.put("constraints", constraints);
            out.add(row);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("scenario", scenario);
        doc.put("states", out.size());
        doc.put("millis", (System.nanoTime() - t0) / 1_000_000);
        doc.put("rows", out);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(Scenarios.baselineFile(scenario).toFile(), doc);
        System.out.println("[mode1-baseline] scenario " + scenario + ": " + out.size() + " states, "
                + out.stream().filter(m -> Boolean.TRUE.equals(m.get("consistent"))).count() + " consistent, "
                + doc.get("millis") + " ms");
    }
}
