package uncertaintree.mode2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.framework.views.View;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.multimodelocl.evaluator.Value;
import uncertainty.Uncertainty;
import uncertainty.UncertaintyAnnotationRepository;
import uncertaintree.stateextraction.PossibleValue;
import uncertaintree.stateextraction.ResolvedUncertaintyDimension;
import uncertaintree.stateextraction.UncertaintyDimensionResolver;
import uncertaintree.utils.VsumUtils;
import uncertaintree.vsum.IdAttributeHandleStrategy;
import uncertaintree.vsum.execution.CopyingVitruvViewProvider;
import uncertaintree.vsum.execution.IdBasedVsumObjectResolver;
import uncertaintree.vsum.execution.VitruvTestViewDefinition;
import uncertaintree.vsum.execution.VsumViewSession;

/**
 * Mode 2 on UncertainTree's inputs, exactly Mode 1's: the nominal VSUM folder, the change
 * propagation specifications, the view root types (including {@code UncertaintyAnnotationRepository})
 * and the OCL constraints (MultiModelOCL, unchanged). Nothing about the case study is coded here.
 * The analysis itself is {@link Mode2Analysis}; this class is its UncertainTree backend
 * (Vitruvius 3.1.2): uncertainty annotations resolved by UncertainTree's
 * {@code UncertaintyDimensionResolver}, isolated checkouts as Mode 1 makes them, Mode 1's handles.
 */
public final class Mode2Engine implements Mode2Backend {

    /** Analysis inputs (Mode 1's, plus where to write the result). */
    public static final class Config extends Mode2Analysis.Options {
        public Path vsumState;
        public List<ChangePropagationSpecification> cps;
        public List<Class<?>> rootTypes;
        public Path oclFile;
    }

    /** An attribute assignment on an element, identified like Mode 1 does (IdAttributeHandleStrategy). */
    public static final class AttributeChange extends Mode2Analysis.AttributeChange {
        public AttributeChange() {}

        public AttributeChange(String element, String attribute, String value) {
            super(element, attribute, value);
        }
    }

    private final Config config;
    private final VsumUtils util = new VsumUtils();
    private final IdAttributeHandleStrategy handles = new IdAttributeHandleStrategy();
    private final Mode2Analysis analysis;
    private List<OclPipeline> invariants;
    private Map<String, EPackage> packages;
    /** Handle of an annotated element, to reach a checkout's resource set after a commit. */
    private String anchor;

    public Mode2Engine(Config config) {
        this.config = config;
        this.analysis = new Mode2Analysis(this, config);
    }

    public List<UncertaintySource> sources() {
        return analysis.sources();
    }

    public Mode2Result analyze() throws Exception {
        return analysis.analyze();
    }

    // ---------------------------------------------------------------- backend

    @Override
    public List<Annotation> annotations() throws Exception {
        invariants = new ArrayList<>();
        List<String> texts = invariants(config.oclFile);
        for (int k = 0; k < texts.size(); k++) {
            invariants.add(new OclPipeline(texts.get(k), k));
        }
        VirtualModel baseline = util.createDefaultVirtualModel(config.vsumState, config.cps);
        View view = util.getDefaultView(baseline, config.rootTypes);
        UncertaintyAnnotationRepository repository =
                view.getRootObjects(UncertaintyAnnotationRepository.class).iterator().next();
        packages = packagesOf(view);
        UncertaintyDimensionResolver resolver = new UncertaintyDimensionResolver();
        List<Annotation> list = new ArrayList<>();
        for (Uncertainty u : repository.getUncertainties()) {
            if (u == null || u.getUncertaintyLocation() == null) {
                continue;
            }
            for (EObject element : u.getUncertaintyLocation().getReferencedComponents()) {
                ResolvedUncertaintyDimension dim = resolver.resolve(u, element);
                if (dim == null || dim.getAttributeName() == null) {
                    list.add(new Annotation(u.getId(), element, null, null, List.of()));
                    continue;
                }
                List<Number> values = new ArrayList<>();
                for (PossibleValue v : dim.getPossibleValues()) {
                    values.add((Number) v.getValue());
                }
                if (anchor == null) {
                    try {
                        anchor = handles.createHandle(element);
                    } catch (RuntimeException e) {
                        // no handle: try the next annotated element
                    }
                }
                list.add(new Annotation(u.getId(), element, dim.getAttributeName(), dim.getJavaType(), values));
            }
        }
        return list;
    }

    @Override
    public Session checkout() {
        VitruvTestViewDefinition def = new VitruvTestViewDefinition(config.vsumState, config.cps, config.rootTypes);
        VsumViewSession session = new CopyingVitruvViewProvider(util, new IdBasedVsumObjectResolver()).checkoutIsolated(def);
        return new Session() {
            @Override
            public EObject resolve(String handle) {
                return session.resolve(handle);
            }

            @Override
            public void commit() {
                session.commit();
            }

            @Override
            public Collection<Resource> resources() {
                return anchor == null ? List.of() : session.resolve(anchor).eResource().getResourceSet().getResources();
            }

            @Override
            public Map<String, Boolean> evaluate(Collection<Resource> resources, Timing timing) {
                InMemoryMetamodelWrapper wrapper = new InMemoryMetamodelWrapper(packages, resources);
                Map<String, Boolean> verdicts = new LinkedHashMap<>();
                for (OclPipeline inv : invariants) {
                    long t = System.nanoTime();
                    verdicts.put(inv.name, inv.evaluate(wrapper, (stage, nanos) -> timing.stage(inv.name, stage, nanos)));
                    timing.invariant(inv.name, System.nanoTime() - t);
                }
                return verdicts;
            }

            @Override
            public void close() {
                session.close();
            }
        };
    }

    @Override
    public String handle(EObject element) {
        return handles.createHandle(element);
    }

    @Override
    public boolean inStructure(EObject element) {
        return element.eClass().getEPackage() != uncertainty.UncertaintyPackage.eINSTANCE;
    }

    private static Map<String, EPackage> packagesOf(View view) {
        Map<String, EPackage> map = new LinkedHashMap<>();
        for (EObject root : view.getRootObjects()) {
            map.putIfAbsent(root.eClass().getEPackage().getName(), root.eClass().getEPackage());
            for (TreeIterator<EObject> it = root.eAllContents(); it.hasNext(); ) {
                EPackage p = it.next().eClass().getEPackage();
                map.putIfAbsent(p.getName(), p);
            }
        }
        return map;
    }

    // ---------------------------------------------------------------- OCL

    /** Splits an OCL file into its "context ... inv ..." blocks. */
    public static List<String> invariants(Path oclFile) throws Exception {
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

    static boolean verdict(Value v) {
        Object o = v == null ? null : v.getValue();
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof List) {
            for (Object e : (List<?>) o) {
                if (!Boolean.TRUE.equals(e) && !"true".equals(String.valueOf(e))) {
                    return false;
                }
            }
            return true;
        }
        return "true".equals(String.valueOf(o));
    }
}
