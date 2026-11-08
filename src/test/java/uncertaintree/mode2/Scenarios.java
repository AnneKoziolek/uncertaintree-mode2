package uncertaintree.mode2;

import brakesystem.Brakesystem;
import cad.CAD_Model;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import mir.reactions.brakesystem2cad.Brakesystem2cadChangePropagationSpecification;
import mir.reactions.cad2brakesystem.Cad2brakesystemChangePropagationSpecification;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import simulink.SimuLinkPackage;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.dsls.reactions.runtime.correspondence.CorrespondencePackage;
import uncertainty.UncertaintyAnnotationRepository;
import uncertaintree.mode2.cps1util.CopyCorrespondencesUtils;
import uncertaintree.mode2.cps1util.EnsureTestDirectoryStructureUtils;
import uncertaintree.mode2.cps1util.UuidUtils;
import uncertaintree.mode2.cps1util.VitruviusModelsMapUtils;

/**
 * Scenarios on UncertainTree's brake example, shared by the Mode-1 baseline and Mode 2.
 * A = CPS1 (diameter); B = CPS1's model state plus a centering-diameter annotation
 * (src/test/resources/scenarioB). These are the inputs Mode 1 takes; nothing else.
 */
final class Scenarios {
    static final Path CPS1 = Path.of("src/test/resources/cps1");
    static final List<Class<?>> ROOT_TYPES =
            List.of(Brakesystem.class, CAD_Model.class, UncertaintyAnnotationRepository.class);

    private Scenarios() {}

    static List<ChangePropagationSpecification> cps() {
        return List.of(new Cad2brakesystemChangePropagationSpecification(),
                new Brakesystem2cadChangePropagationSpecification());
    }

    static void registerPackages() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
        var pkgReg = EPackage.Registry.INSTANCE;
        pkgReg.put(cad.CadPackage.eNS_URI, cad.CadPackage.eINSTANCE);
        pkgReg.put(brakesystem.BrakesystemPackage.eNS_URI, brakesystem.BrakesystemPackage.eINSTANCE);
        pkgReg.put(CorrespondencePackage.eNS_URI, CorrespondencePackage.eINSTANCE);
        pkgReg.put(uncertainty.UncertaintyPackage.eNS_URI, uncertainty.UncertaintyPackage.eINSTANCE);
        pkgReg.put(SimuLinkPackage.eNS_URI, SimuLinkPackage.eINSTANCE);
    }

    static Path oclFile() {
        return CPS1.resolve("overlapping.ocl").toAbsolutePath();
    }

    /** Prepares the nominal VSUM folder of a scenario ("A" or "B") under target/, like CPS1's setup. */
    static Path prepare(String scenario, String suffix) throws Exception {
        Path vsumState = Path.of("target", "scenario" + scenario + "-" + suffix).toAbsolutePath();
        Path original = CPS1.resolve("original_model_state").toAbsolutePath();
        Path meta = vsumState.resolve("vsum");
        EnsureTestDirectoryStructureUtils.ensureDirectoryExistsAndClear(vsumState);
        EnsureTestDirectoryStructureUtils.copyOriginalStateToVsumRoot(original, vsumState);
        if ("B".equals(scenario)) {
            Files.copy(Path.of("src/test/resources/scenarioB/uncertainties.uncertainty"),
                    vsumState.resolve("uncertainties.uncertainty"), StandardCopyOption.REPLACE_EXISTING);
        }
        VitruviusModelsMapUtils.createModelsModelsFile(vsumState);
        UuidUtils.writeUuidFileFromModelDirectory(vsumState, meta.resolve("uuid.uuid"));
        CopyCorrespondencesUtils.copyAndAdjustCorrespondenceFile(original, meta);
        return vsumState;
    }

    static Path baselineFile(String scenario) {
        return Path.of("target", "mode1-baseline-" + scenario + ".json").toAbsolutePath();
    }
}
