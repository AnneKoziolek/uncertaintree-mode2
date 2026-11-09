package uncertaintree.mode2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prepares the nominal VSUM folder of a scenario ("A", "B" on CPS1, "C" on CPS3) under target/ and writes a
 * worker request for it, so that {@code scripts/start-demo-worker.sh} can start the worker with a
 * warm-up and a request file ready to POST. Runs in a plain JVM (no instrumentation needed).
 *
 * <p>Usage: {@code PrepareDemoRequest <scenario> <request file> [solver port]}
 */
public final class PrepareDemoRequest {

    private PrepareDemoRequest() {}

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "A";
        Path out = Path.of(args.length > 1 ? args[1] : "target/demo-request-" + scenario + ".json").toAbsolutePath();
        int solverPort = args.length > 2 ? Integer.parseInt(args[2]) : 9420;
        Scenarios.registerPackages();
        Mode2Worker.Request req = new Mode2Worker.Request();
        req.scenario = scenario + "-demo";
        req.vsumState = Scenarios.prepare(scenario, "demo").toString();
        req.oclFile = Scenarios.oclFile(scenario).toString();
        req.cps = List.of("mir.reactions.cad2brakesystem.Cad2brakesystemChangePropagationSpecification",
                "mir.reactions.brakesystem2cad.Brakesystem2cadChangePropagationSpecification");
        req.rootTypes = List.of("brakesystem.Brakesystem", "cad.CAD_Model", "uncertainty.UncertaintyAnnotationRepository");
        req.packages = List.of("cad.CadPackage", "brakesystem.BrakesystemPackage",
                "tools.vitruv.dsls.reactions.runtime.correspondence.CorrespondencePackage",
                "uncertainty.UncertaintyPackage", "simulink.SimuLinkPackage");
        req.solverPort = solverPort;
        Files.createDirectories(out.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), req);
        System.out.println("request written: " + out);
    }
}
