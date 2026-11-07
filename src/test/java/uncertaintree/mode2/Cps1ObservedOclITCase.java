package uncertaintree.mode2;

import brakesystem.BrakeDisk;
import brakesystem.Brakesystem;
import cad.CAD_Model;
import edu.gmu.swe.knarr.runtime.PathConstraintListener;
import edu.gmu.swe.knarr.runtime.PathUtils;
import edu.gmu.swe.knarr.runtime.Symbolicator;
import edu.neu.ccs.prl.galette.internal.runtime.symbolic.SymbolicListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import za.ac.sun.cs.green.expr.Expression;

/**
 * Mode-2 probe 2: observed evaluation of CPS1's OCL constraints.
 *
 * <p>After the symbolic commit of probe 1, MultiModelOCL (unchanged) evaluates each
 * invariant of {@code overlapping.ocl} on the live, tagged model in the same JVM.
 * With {@code tools.vitruv.multimodelocl.} among the recording prefixes, its
 * comparisons on tagged values become path constraints: the verdict as a constraint
 * over the diameter (NIER Sec. 4.3, "evaluators expose their verdict as a constraint").
 */
public class Cps1ObservedOclITCase {

    @BeforeAll
    static void registerPackages() {
        Cps1SymbolicDiameterITCase.registerPackages();
    }

    /** Splits an OCL file into its "context ... inv ..." blocks. */
    static List<String> invariants(Path oclFile) throws Exception {
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

    @Test
    void oclVerdictBecomesConstraintOverDiameter() throws Exception {
        Path vsumState = Cps1SymbolicDiameterITCase.prepareVsumState("cps1-vsum-ocl");
        VsumUtils util = new VsumUtils();
        VirtualModel baseline = util.createDefaultVirtualModel(vsumState, Cps1SymbolicDiameterITCase.CPS);
        View bv = util.getDefaultView(baseline, Cps1SymbolicDiameterITCase.ROOT_TYPES);
        Brakesystem bs = bv.getRootObjects(Brakesystem.class).iterator().next();
        BrakeDisk disk = bs.getBrakeComponents().stream()
                .filter(BrakeDisk.class::isInstance).map(BrakeDisk.class::cast)
                .filter(d -> Cps1SymbolicDiameterITCase.BRAKE_DISC_ID.equals(d.getId())).findFirst().orElseThrow();
        String diskHandle = new IdAttributeHandleStrategy().createHandle(disk);
        List<String> invariants = invariants(Cps1SymbolicDiameterITCase.CPS1.resolve("overlapping.ocl"));
        System.out.println("[mode2-ocl] " + invariants.size() + " invariants");

        Map<String, EPackage> packages = new LinkedHashMap<>();
        packages.put("cad", cad.CadPackage.eINSTANCE);
        packages.put("brakesystem", brakesystem.BrakesystemPackage.eINSTANCE);

        VitruvTestViewDefinition def = new VitruvTestViewDefinition(
                vsumState, Cps1SymbolicDiameterITCase.CPS, Cps1SymbolicDiameterITCase.ROOT_TYPES);
        try (VsumViewSession session =
                new CopyingVitruvViewProvider(util, new IdBasedVsumObjectResolver()).checkoutIsolated(def)) {
            BrakeDisk d = (BrakeDisk) session.resolve(diskHandle);
            SymbolicListener.setListener(new PathConstraintListener());
            d.setDiameterInMM(Symbolicator.symbolic("diameterInMM", 325));
            long t0 = System.nanoTime();
            session.commit();
            System.out.printf("[mode2-ocl] commit %.1f ms, PC: %s%n", (System.nanoTime() - t0) / 1e6,
                    Cps1SymbolicDiameterITCase.pc());

            BrakeDisk committed = (BrakeDisk) session.resolve(diskHandle);
            InMemoryMetamodelWrapper wrapper = new InMemoryMetamodelWrapper(
                    packages, committed.eResource().getResourceSet().getResources());
            for (String inv : invariants) {
                String name = inv.substring(0, inv.indexOf(':') + 1).replaceAll("\\s+", " ");
                int before = PathUtils.getCurPC().branchConstraints.size();
                long t1 = System.nanoTime();
                OCLCompiler compiler = new OCLCompiler(wrapper, null);
                Value v = compiler.compile(inv);
                long ms = (System.nanoTime() - t1) / 1_000_000;
                List<Expression> added = new ArrayList<>(
                        PathUtils.getCurPC().branchConstraints.subList(before,
                                PathUtils.getCurPC().branchConstraints.size()));
                System.out.println("[mode2-ocl] " + name + " -> " + (v == null ? "null" : v.getValue())
                        + " errors=" + compiler.hasErrors() + " (" + ms + " ms), new constraints: " + added.size());
                for (int k = 0; k < Math.min(added.size(), 12); k++) {
                    System.out.println("[mode2-ocl]     " + added.get(k));
                }
            }
            SymbolicListener.setListener(null);
            Assertions.assertTrue(true);
        } finally {
            SymbolicListener.setListener(null);
            Symbolicator.reset();
        }
    }
}
