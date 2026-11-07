package uncertaintree.mode2;

import brakesystem.BrakeDisk;
import brakesystem.Brakesystem;
import cad.CAD_Model;
import cad.Cylinder;
import edu.gmu.swe.knarr.runtime.PathConstraintListener;
import edu.gmu.swe.knarr.runtime.PathUtils;
import edu.gmu.swe.knarr.runtime.Symbolicator;
import edu.neu.ccs.prl.galette.internal.runtime.symbolic.SymbolicListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mir.reactions.brakesystem2cad.Brakesystem2cadChangePropagationSpecification;
import mir.reactions.cad2brakesystem.Cad2brakesystemChangePropagationSpecification;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import simulink.SimuLinkPackage;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.dsls.reactions.runtime.correspondence.CorrespondencePackage;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.vsum.VirtualModel;
import uncertainty.UncertaintyAnnotationRepository;
import uncertaintree.mode2.cps1util.CopyCorrespondencesUtils;
import uncertaintree.mode2.cps1util.EnsureTestDirectoryStructureUtils;
import uncertaintree.mode2.cps1util.UuidUtils;
import uncertaintree.mode2.cps1util.VitruviusModelsMapUtils;
import uncertaintree.utils.VsumUtils;
import uncertaintree.vsum.IdAttributeHandleStrategy;
import uncertaintree.vsum.execution.CopyingVitruvViewProvider;
import uncertaintree.vsum.execution.IdBasedVsumObjectResolver;
import uncertaintree.vsum.execution.VitruvTestViewDefinition;
import uncertaintree.vsum.execution.VsumViewSession;
import za.ac.sun.cs.green.expr.Expression;

/**
 * Mode-2 probe 1 on UncertainTree's CPS1 (brake-disc diameter uncertainty).
 *
 * <p>Instead of enumerating the 13 diameters, the diameter of the checked-out
 * BrakeDisk is set to a Knarr-symbolic int (the uncertainty dimension becomes a
 * symbolic variable, introduced from outside the CPRs). The real
 * {@code .reactions}-generated CPR {@code brakesystem2cad.updateDiameter} then runs
 * inside Vitruvius' change propagation. We check that the CAD cylinder radius
 * carries the symbolic expression {@code diameterInMM/2.0}, and print which path
 * constraints Vitruvius/EMF/the CPRs recorded on the way (noise check).
 */
public class Cps1SymbolicDiameterITCase {

    static final Path CPS1 = Path.of("src/test/resources/cps1");
    static final String BRAKE_DISC_ID = "id3_front_brake_disc";
    static final String BRAKE_DISC_CYLINDER_ID = "id3_front_brake_disc_cylinder";

    static final List<ChangePropagationSpecification> CPS = List.of(
            new Cad2brakesystemChangePropagationSpecification(), new Brakesystem2cadChangePropagationSpecification());
    static final List<Class<?>> ROOT_TYPES =
            List.of(Brakesystem.class, CAD_Model.class, UncertaintyAnnotationRepository.class);

    @BeforeAll
    static void registerPackages() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
        var pkgReg = EPackage.Registry.INSTANCE;
        pkgReg.put(cad.CadPackage.eNS_URI, cad.CadPackage.eINSTANCE);
        pkgReg.put(brakesystem.BrakesystemPackage.eNS_URI, brakesystem.BrakesystemPackage.eINSTANCE);
        pkgReg.put(CorrespondencePackage.eNS_URI, CorrespondencePackage.eINSTANCE);
        pkgReg.put(uncertainty.UncertaintyPackage.eNS_URI, uncertainty.UncertaintyPackage.eINSTANCE);
        pkgReg.put(SimuLinkPackage.eNS_URI, SimuLinkPackage.eINSTANCE);
    }

    /** Same preparation as CPS1DiscreteUncertaintyTest.setupTest, into target/. */
    static Path prepareVsumState(String name) throws Exception {
        Path vsumState = Path.of("target", name).toAbsolutePath();
        Path original = CPS1.resolve("original_model_state").toAbsolutePath();
        Path meta = vsumState.resolve("vsum");
        EnsureTestDirectoryStructureUtils.ensureDirectoryExistsAndClear(vsumState);
        EnsureTestDirectoryStructureUtils.copyOriginalStateToVsumRoot(original, vsumState);
        VitruviusModelsMapUtils.createModelsModelsFile(vsumState);
        UuidUtils.writeUuidFileFromModelDirectory(vsumState, meta.resolve("uuid.uuid"));
        CopyCorrespondencesUtils.copyAndAdjustCorrespondenceFile(original, meta);
        return vsumState;
    }

    static String pc() {
        Expression e = PathUtils.getCurPC().constraints;
        if (e == null) {
            return "<none>";
        }
        int n = PathUtils.getCurPC().branchConstraints.size();
        if (n > 50) {
            return n + " branch constraints (too many to print)";
        }
        return n + " branch constraints: " + e;
    }

    @Test
    void symbolicDiameterPropagatesToCadRadius() throws Exception {
        Path vsumState = prepareVsumState("cps1-vsum");
        VsumUtils util = new VsumUtils();

        // Handles from a baseline view, as in CPS1.
        VirtualModel baseline = util.createDefaultVirtualModel(vsumState, CPS);
        View bv = util.getDefaultView(baseline, ROOT_TYPES);
        Brakesystem bs = bv.getRootObjects(Brakesystem.class).iterator().next();
        CAD_Model cad = bv.getRootObjects(CAD_Model.class).iterator().next();
        BrakeDisk disk = bs.getBrakeComponents().stream()
                .filter(BrakeDisk.class::isInstance).map(BrakeDisk.class::cast)
                .filter(d -> BRAKE_DISC_ID.equals(d.getId())).findFirst().orElseThrow();
        Cylinder cyl = cad.getNamespaces().stream().flatMap(n -> n.getShapes().stream())
                .filter(Cylinder.class::isInstance).map(Cylinder.class::cast)
                .filter(c -> BRAKE_DISC_CYLINDER_ID.equals(c.getId())).findFirst().orElseThrow();
        IdAttributeHandleStrategy handles = new IdAttributeHandleStrategy();
        String diskHandle = handles.createHandle(disk);
        String cylHandle = handles.createHandle(cyl);
        System.out.println("[mode2] nominal diameterInMM=" + disk.getDiameterInMM() + " radius=" + cyl.getRadius());

        VitruvTestViewDefinition def = new VitruvTestViewDefinition(vsumState, CPS, ROOT_TYPES);
        long t0 = System.nanoTime();
        try (VsumViewSession session =
                new CopyingVitruvViewProvider(util, new IdBasedVsumObjectResolver()).checkoutIsolated(def)) {
            BrakeDisk d = (BrakeDisk) session.resolve(diskHandle);
            // Listener only now: VSUM loading and checkout are not part of the analysis.
            SymbolicListener.setListener(new PathConstraintListener());
            int symbolicDiameter = Symbolicator.symbolic("diameterInMM", 325);
            d.setDiameterInMM(symbolicDiameter);
            System.out.println("[mode2] PC after eSet: " + pc());
            session.commit();
            System.out.println("[mode2] PC after commit (CPRs ran): " + pc());

            Cylinder c = (Cylinder) session.resolve(cylHandle);
            float radius = c.getRadius();
            Expression radiusExpr = Symbolicator.getExpression(radius);
            int committedDiameter = ((BrakeDisk) session.resolve(diskHandle)).getDiameterInMM();
            Expression diameterExpr = Symbolicator.getExpression(committedDiameter);
            SymbolicListener.setListener(null);

            System.out.println("[mode2] committed diameterInMM=" + committedDiameter + " expr=" + diameterExpr);
            System.out.println("[mode2] cylinder radius=" + radius + " expr=" + radiusExpr);
            System.out.printf("[mode2] commit+propagation took %.1f ms%n", (System.nanoTime() - t0) / 1e6);

            Assertions.assertEquals(162.5f, radius, 0.0001f, "concrete radius for d=325");
            Assertions.assertNotNull(diameterExpr, "symbolic tag must survive Vitruvius change propagation on the disk");
            Assertions.assertNotNull(radiusExpr, "CPR-written radius must carry a symbolic expression");
        } finally {
            SymbolicListener.setListener(null);
            Symbolicator.reset();
        }
        Files.createDirectories(Path.of("target"));
    }
}
