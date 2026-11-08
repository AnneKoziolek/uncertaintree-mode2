package uncertaintree.mode2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * G3 (NIER Sec. 6.2), Mode-2 half: Mode 2 on the width scenarios of -Dmode2.g3, each
 * compared point by point with G3Mode1Test's baseline (Mode2EngineITCase#run). Writes
 * target/g3-summary.json: states and time of Mode 1, executions, solver queries and time of
 * Mode 2, number of regions.
 */
@EnabledIfSystemProperty(named = "mode2.g3", matches = ".+")
public class G3Mode2ITCase {
    @Test
    @SuppressWarnings("unchecked")
    void widths() throws Exception {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Scenarios.registerPackages();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String s : Scenarios.g3Scenarios()) {
            Mode2Result r = Mode2EngineITCase.run(s);
            Map<String, Object> m1 = json.readValue(Scenarios.baselineFile(s).toFile(), Map.class);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scenario", s);
            row.put("mode1States", m1.get("states"));
            row.put("mode1Millis", m1.get("millis"));
            row.put("mode2Executions", r.stats.concreteExecutions);
            row.put("mode2SolverQueries", r.stats.solverQueries);
            row.put("mode2SolverMillis", r.stats.solverMillis);
            row.put("mode2Millis", r.stats.totalMillis);
            row.put("regions", r.regions.size());
            row.put("divergences", r.stats.divergences);
            rows.add(row);
            System.out.println("[g3] " + row);
            json.writeValue(Path.of("target", "g3-summary.json").toFile(), rows);
        }
    }
}
