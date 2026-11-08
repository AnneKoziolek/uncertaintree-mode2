package uncertaintree.mode2;

/**
 * One uncertainty dimension that Mode 2 treats as a symbolic variable: an attribute of
 * one model element, with the domain taken from the uncertainty annotation (resolved by
 * UncertainTree's {@code UncertaintyDimensionResolver}, exactly as in Mode 1).
 */
public final class UncertaintySource {
    /** Name of the symbolic variable (Green/Z3), derived from uncertainty id and attribute. */
    public final String label;

    public final String uncertaintyId;
    /** Handle of the element (IdAttributeHandleStrategy), as Mode 1 uses it. */
    public final String handle;

    public final String attribute;
    public final int min;
    public final int max;
    /** Value in the nominal model state. */
    public final int nominal;

    public UncertaintySource(String label, String uncertaintyId, String handle, String attribute, int min, int max,
            int nominal) {
        this.label = label;
        this.uncertaintyId = uncertaintyId;
        this.handle = handle;
        this.attribute = attribute;
        this.min = min;
        this.max = max;
        this.nominal = nominal;
    }

    public boolean hasAlternatives() {
        return min < max;
    }

    @Override
    public String toString() {
        return label + " = " + handle + "." + attribute + " in [" + min + ", " + max + "], nominal " + nominal;
    }
}
