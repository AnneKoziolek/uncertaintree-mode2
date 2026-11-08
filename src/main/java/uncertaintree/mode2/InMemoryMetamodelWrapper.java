package uncertaintree.mode2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import tools.vitruv.multimodelocl.pipeline.MetamodelWrapperInterface;

/**
 * MultiModelOCL view of live, in-memory model roots (Mode 2, observed evaluation).
 *
 * <p>MultiModelOCL's own {@code MetamodelWrapper} loads XMI files and {@code .ecore}
 * metamodels as dynamic packages. For Mode 2 the evaluator must instead see the
 * objects of the checked-out VSUM after propagation, whose attribute values carry
 * Galette tags (lost on XMI serialization), typed by the generated EPackages.
 */
public final class InMemoryMetamodelWrapper implements MetamodelWrapperInterface {
    private final Map<String, EPackage> packages = new LinkedHashMap<>();
    private final List<EObject> roots = new ArrayList<>();
    private final List<EObject> all = new ArrayList<>();

    /** @param packages metamodel name as used in OCL (e.g. {@code cad}) to generated EPackage */
    public InMemoryMetamodelWrapper(Map<String, EPackage> packages, Collection<Resource> resources) {
        this.packages.putAll(packages);
        for (Resource r : resources) {
            for (EObject root : r.getContents()) {
                roots.add(root);
                all.add(root);
                for (TreeIterator<EObject> it = root.eAllContents(); it.hasNext(); ) {
                    all.add(it.next());
                }
            }
        }
    }

    @Override
    public EClass resolveEClass(String metamodel, String className) {
        EPackage p = packages.get(metamodel);
        if (p == null) {
            return null;
        }
        EClassifier c = p.getEClassifier(className);
        return c instanceof EClass ? (EClass) c : null;
    }

    @Override
    public EClass resolveEClassByShortName(String className) {
        for (EPackage p : packages.values()) {
            EClassifier c = p.getEClassifier(className);
            if (c instanceof EClass) {
                return (EClass) c;
            }
        }
        return null;
    }

    @Override
    public List<EObject> getAllInstances(EClass eClass) {
        List<EObject> result = new ArrayList<>();
        for (EObject o : all) {
            if (eClass.isSuperTypeOf(o.eClass())) {
                result.add(o);
            }
        }
        return result;
    }

    @Override
    public Set<String> getAvailableMetamodels() {
        return packages.keySet();
    }

    @Override
    public String getInstanceNameByIndex(int index) {
        return index >= 0 && index < roots.size() ? roots.get(index).eClass().getName() + "#" + index : "?";
    }

    @Override
    public List<EObject> getAllRootObjects() {
        return roots;
    }

    @Override
    public EObject getContextObjectByIndex(int index) {
        return index >= 0 && index < roots.size() ? roots.get(index) : null;
    }
}
