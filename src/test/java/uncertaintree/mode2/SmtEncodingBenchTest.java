package uncertaintree.mode2;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Params;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Diagnosis only (runs with -Dsmt.dir=<dumps> -Dsmt.dirInt=<rewritten dumps>): times Z3 on the
 * slow queries dumped by Mode2SolverServer, in their bit-vector encoding and rewritten to
 * integers, and checks that both give the same answer. Skipped in normal builds.
 */
class SmtEncodingBenchTest {
    @Test
    void compareEncodings() throws Exception {
        String bv = System.getProperty("smt.dir");
        String in = System.getProperty("smt.dirInt");
        Assumptions.assumeTrue(bv != null && in != null && !bv.isBlank(), "diagnosis not requested");
        List<Path> files;
        try (var s = Files.list(Path.of(bv))) {
            files = s.filter(p -> p.toString().endsWith(".smt2")).sorted().collect(Collectors.toList());
        }
        long totalBv = 0;
        long totalInt = 0;
        try (Context ctx = new Context()) {
            for (Path f : files) {
                long[] tb = new long[1];
                Status sb = check(ctx, Files.readString(f), tb);
                long[] ti = new long[1];
                Status si = check(ctx, Files.readString(Path.of(in).resolve(f.getFileName())), ti);
                totalBv += tb[0];
                totalInt += ti[0];
                System.out.printf("[smt-bench] %-36s bv %6.0f ms %-13s int %6.0f ms %s%n", f.getFileName(), tb[0] / 1e6, sb,
                        ti[0] / 1e6, si);
                Assertions.assertEquals(sb, si, "same answer in both encodings: " + f.getFileName());
            }
        }
        System.out.printf("[smt-bench] total: bv %.0f ms, int %.0f ms, %d queries%n", totalBv / 1e6, totalInt / 1e6, files.size());
    }

    private static Status check(Context ctx, String smt, long[] nanos) {
        Solver solver = ctx.mkSolver();
        Params p = ctx.mkParams();
        p.add("timeout", 30000);
        solver.setParameters(p);
        for (BoolExpr a : ctx.parseSMTLIB2String(smt.replace("(check-sat)", ""), null, null, null, null)) {
            solver.add(a);
        }
        long t = System.nanoTime();
        Status s = solver.check();
        nanos[0] = System.nanoTime() - t;
        return s;
    }
}
