package uncertaintree.mode2;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;

/**
 * What {@link Mode2Analysis} needs from a VSUM framework: the annotated attributes, an isolated
 * copy of the nominal models per path, propagation by commit, and a verdict per OCL invariant.
 * Everything else (realization of symbolic values, exploration, regions, solver) is in the core
 * and independent of the Vitruvius version and of the OCL evaluator. Implementations: the
 * UncertainTree backend in this repository (Vitruvius 3.1.2, MultiModelOCL, uncertainty model)
 * and a demonstrator backend (Vitruvius 4, VitruvOCL) kept with the demonstrator integration.
 */
public interface Mode2Backend {

    /** One annotated attribute dimension on the nominal model, as the annotation states it. */
    final class Annotation {
        /** Identifier of the uncertainty; annotations with the same id and a real type form one source. */
        public final String uncertainty;
        /** The annotated element in the nominal model (used for its handle and nominal value). */
        public final EObject element;
        /** Attribute name; null if the annotation is not an attribute dimension. */
        public final String attribute;
        /** Java type of the attribute (int, double, ...); null if not an attribute dimension. */
        public final Class<?> type;
        /** The values the annotation allows; the analysis uses their minimum and maximum. */
        public final List<Number> possibleValues;

        public Annotation(String uncertainty, EObject element, String attribute, Class<?> type,
                List<Number> possibleValues) {
            this.uncertainty = uncertainty;
            this.element = element;
            this.attribute = attribute;
            this.type = type;
            this.possibleValues = possibleValues;
        }
    }

    /** Timing sink for invariant evaluation: total per invariant and per stage of the evaluator. */
    interface Timing {
        void invariant(String invariant, long nanos);

        void stage(String invariant, String stage, long nanos);
    }

    /** An isolated copy of the nominal models with the CPRs registered. */
    interface Session extends AutoCloseable {
        /** The element with this handle in the copy (after a commit: the propagated one). */
        EObject resolve(String handle);

        /** Propagates the changes made since the checkout (or the last commit) through the CPRs. */
        void commit();

        /** The copy's model resources after the last commit. */
        Collection<Resource> resources();

        /** Verdict per invariant, in the order of the invariants, on these resources. */
        Map<String, Boolean> evaluate(Collection<Resource> resources, Timing timing);

        @Override
        void close();
    }

    /** The annotated attribute dimensions, read from the nominal models. */
    List<Annotation> annotations() throws Exception;

    /** A fresh isolated copy of the nominal models. */
    Session checkout();

    /** A stable handle of an element across copies; throws a RuntimeException if it has none. */
    String handle(EObject element);

    /** Whether the element counts for the structural comparison (false e.g. for the uncertainty model). */
    default boolean inStructure(EObject element) {
        return true;
    }
}
