# uncertaintree-mode2

Mode 2 of UncertainTree: symbolic execution of consistency preservation with CoCoPath-style concolic exploration on Jon Bell's Galette/Knarr (`knarr-integration`), applied to UncertainTree's brake example.

**Conceptual insights** (what is tainted and why, representative versus nominal value, fixpoint check, filters, library semantics, extents) are recorded in the stack repository's [`docs/mode2-concepts.md`](https://gitlab.kit.edu/anne.koziolek/uncertaintree-stack/-/blob/main/docs/mode2-concepts.md). Sessions working here add new insights there; paper-writing sessions start from it. Steps and measurements: `docs/mode2-log.md` in the same repository.

## Contents

| Path | Role |
|---|---|
| `src/main/java/uncertaintree/mode2/Mode2Engine.java` | Mode 2 on Mode 1's inputs (VSUM folder, CPRs, root types, OCL file); sources from the uncertainty annotations |
| `Mode2Result.java` | JSON result: regions with verdicts and extents, affected attributes, nominal/fixpoint check, statistics |
| `Mode2SolverServer.java`, `Mode2Solver.java` | Z3 in a separate plain JVM; one socket per analysis, `TCP_NODELAY`; the worker keeps one solver process for its lifetime; diagnosis properties `mode2.dumpQueries` (every request, for offline replay) and `mode2.dumpSlowQueries` (SMT-LIB); `mode2.bounds` = `search` (default) or `optimize` for region bounds |
| `OclPipeline.java` | MultiModelOCL's stages (parse, symbol table, type check, evaluation) with the parse tree cached per invariant text; timing per stage in `phaseMillis` |
| `Mode2Worker.java` | Warm worker over HTTP (`POST /analyze`, `GET /health`); start with `scripts/run-worker.sh`, optionally with a warm-up request that also fills the parse cache |
| `InMemoryMetamodelWrapper.java` | MultiModelOCL on live, tagged models |
| `src/test/.../Mode1BaselineTest.java` | UncertainTree's real Mode 1 (plain JVM), baseline per state |
| `src/test/.../Mode2EngineITCase.java` | Mode 2 on scenarios A and B, compared with the baseline |
| `src/test/resources/cps1`, `scenarioB` | CPS1 model state (copied from UncertainTreeDev) and scenario B's annotations |

## Build and run

Requires the stack's Bell builds (Green, Galette, Knarr) in the separate Maven repository `/workspace/.m2-bell`, UncertainTreeDev's brake case installed there, and Knarr's instrumented JDK (`/workspace/knarr-bell/knarr/target/galette/java`). Commands: `docs/mode2-log.md`, section "Reproduce".

```
mvn -B -Dmaven.repo.local=/workspace/.m2-bell verify
```

Clear `target/galette-cache` whenever `branch.prefixes` in the pom changes: Galette's transformation cache does not include it in its key.
