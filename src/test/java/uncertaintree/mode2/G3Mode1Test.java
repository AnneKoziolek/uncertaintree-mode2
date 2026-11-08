package uncertaintree.mode2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * G3 (NIER Sec. 6.2), Mode-1 half: UncertainTree's real Mode 1 on the width scenarios of
 * -Dmode2.g3 (e.g. W-A-50-50,W-B-10-10-5-5; see Scenarios#widen). Plain JVM; writes
 * target/mode1-baseline-&lt;scenario&gt;.json, which G3Mode2ITCase compares against.
 */
@EnabledIfSystemProperty(named = "mode2.g3", matches = ".+")
class G3Mode1Test {
    @Test
    void baselines() throws Exception {
        Scenarios.registerPackages();
        for (String s : Scenarios.g3Scenarios()) {
            Mode1BaselineTest.baseline(s);
        }
    }
}
