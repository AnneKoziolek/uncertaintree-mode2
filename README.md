# uncertaintree-mode2

Mode 2 of UncertainTree: symbolic execution of consistency preservation with CoCoPath-style concolic exploration on Jon Bell's Galette/Knarr (`knarr-integration`), applied to UncertainTree's brake example.

**Conceptual insights** (what is tainted and why, representative versus nominal value, fixpoint check, filters, library semantics, extents) are recorded in the stack repository's [`docs/mode2-concepts.md`](https://gitlab.kit.edu/anne.koziolek/uncertaintree-stack/-/blob/main/docs/mode2-concepts.md) (KIT GitLab, access on request). Overview and links to all commits: issue #1. Sessions working here add new insights there; paper-writing sessions start from it. Steps and measurements: `docs/mode2-log.md` in the same repository.

## Contents

| Path | Role |
|---|---|
| `core/` | **The Mode-2 core, independent of the Vitruvius version and of the OCL evaluator** (since 2026-11-10): `Mode2Analysis` (exploration, symbolic realization, regions, nominal check, classification of requested points, verdict classes; options: `extents` off for classification only, `maxPaths`; solver limits `mode2.checkTimeoutMs`, `mode2.bisectTimeoutMs`, `mode2.bisectTolerance`, a hard wall-clock limit per Z3 call), `Mode2Backend` (what a VSUM framework supplies: annotations, isolated checkouts, commit, verdicts per invariant), `Mode2Result`, `UncertaintySource`, `Exprs`, `Mode2Solver`, `Mode2SolverServer`. Its own Maven project; `scripts/mvn.sh` installs it first. A second backend, for the brake demonstrator (Vitruvius 4, VitruvOCL), lives with the demonstrator integration in the stack repository (`demonstrator-mode2/`) |
| `src/main/java/uncertaintree/mode2/Mode2Engine.java` | The UncertainTree backend (Vitruvius 3.1.2, MultiModelOCL): Mode 2 on Mode 1's inputs (VSUM folder, CPRs, root types, OCL file); sources from the uncertainty annotations; API unchanged (`Config`, `AttributeChange`, `analyze()`) |
| `core/.../Mode2Result.java` | JSON result: regions with verdicts, extents, probability (uniform prior, box regions) and elements added or removed against the nominal model; affected attributes; dependency check per annotated attribute (independent, rewritten, derived, resolved, untouched); elements varying between regions; nominal/fixpoint check; statistics |
| `core/.../Mode2SolverServer.java`, `Mode2Solver.java` | Z3 in a separate plain JVM; one connection and one incremental Z3 Solver per analysis (`Mode2Solver.endAnalysis`), `TCP_NODELAY`; the worker keeps one solver process for its lifetime; diagnosis properties `mode2.dumpQueries` (every request, for offline replay) and `mode2.dumpSlowQueries` (SMT-LIB); `mode2.bounds` = `search` (default) or `optimize` for region bounds |
| `OclPipeline.java` | MultiModelOCL's stages (parse, symbol table, type check, evaluation) with the parse tree cached per invariant text; timing per stage in `phaseMillis` |
| `Mode2Worker.java` | Warm worker over HTTP (`POST /analyze`, `GET /health`); start with `scripts/run-worker.sh`, optionally with a warm-up request that also fills the parse cache; for the demo, `scripts/start-demo-worker.sh [A|B|C] [port]` prepares a scenario, writes `target/demo-request-<scenario>.json` and starts the worker warmed up with it |
| `InMemoryMetamodelWrapper.java` | MultiModelOCL on live, tagged models |
| `core/.../UncertaintySource.java`, `Exprs.java` | an annotated attribute as a symbolic source (integer, real, or a shift of several real attributes for a grouped annotation); helpers for Green expressions (variables, closure, nonlinearity) |
| `src/test/.../Mode1BaselineTest.java` | UncertainTree's real Mode 1 (plain JVM), baseline per state; constraint names by position in the OCL file (the CLI evaluator reports `unknown`) |
| `src/test/.../Mode2EngineITCase.java` | Mode 2 on scenarios A, B and A with an incoming change (`ventilated := false`), compared with the baseline per state and constraint, probabilities against Mode 1's state shares; scenario D (dependent radius annotation), an incoming change that resolves the diameter, and scenario C (real-valued shift; exact boundary, slices, 1008 Mode-1 grid points) |
| `src/test/.../G3Mode1Test.java`, `G3Mode2ITCase.java` | G3 width series, only with `-Dmode2.g3=W-A-50-50,...` (see `Scenarios#widen`); summary in `target/g3-summary.json` |
| `src/test/.../Mode2WorkerITCase.java` | the worker over HTTP: scenario A cold, then three warm requests; timings per phase |
| `src/test/.../Cps1SymbolicDiameterITCase.java`, `Cps1ObservedOclITCase.java` | probes: the symbolic diameter reaches the CAD radius through the real CPRs; MultiModelOCL's verdict becomes a constraint |
| `src/test/.../Scenarios.java`, `PrepareDemoRequest.java`, `SmtEncodingBenchTest.java` | scenario inputs; a scenario folder and worker request for the demo script; benchmark of the bit-vector and integer encodings on recorded queries |
| `src/test/resources/cps1`, `cps3`, `scenarioB`, `scenarioD` | CPS1 and CPS3 model states (copied from UncertainTreeDev; CPS3 is scenario C: caliper position shift × disc diameter); the annotations of scenarios B (plus centering diameter) and D (plus cylinder radius) |

## Build and run

Requires the stack's Bell builds (Green, Galette, Knarr) in the separate Maven repository `/workspace/.m2-bell`, UncertainTreeDev's brake case installed there, and Knarr's instrumented JDK (`/workspace/knarr-bell/knarr/target/galette/java`). The stack's `scripts/build-mode2-stack.sh` builds all of it (the stack's devcontainer post-create runs it); manual commands: `docs/mode2-log.md`, section "Reproduce".

```
scripts/mvn.sh verify
```

`scripts/mvn.sh` runs Maven with JDK 21 (since 2026-11-09; `MODE2_JAVA_HOME`) and the Bell stack's repository (`MODE2_REPO`); the worker scripts use the same JDK.

Clear `target/galette-cache` whenever `branch.prefixes` in the pom changes: Galette's transformation cache does not include it in its key.
