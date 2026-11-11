package uncertaintree.mode2;

import java.util.ArrayList;
import java.util.List;

/**
 * One uncertainty dimension that Mode 2 treats as a symbolic variable, with the domain taken
 * from the uncertainty annotation (resolved by UncertainTree's {@code UncertaintyDimensionResolver},
 * exactly as in Mode 1).
 *
 * <p>Integer sources (scenarios A, B): the variable is the value of one attribute of one element.
 * Real sources (scenario C, 2026-11-09): either the value of one float or double attribute, or,
 * for an annotation that references several elements (Mode 1's "grouped" dimension with a
 * synchronized offset), a shift {@code δ} applied to all of them: member i gets
 * {@code nominal_i + δ}. Real variables are solved as Z3 reals, so results are exact up to the
 * float rounding of the program.
 */
public final class UncertaintySource {
    /** One annotated attribute that the variable sets. */
    public static final class Member {
        public final String handle;
        public final String attribute;
        /** Value in the nominal model state. */
        public final double nominal;

        public Member(String handle, String attribute, double nominal) {
            this.handle = handle;
            this.attribute = attribute;
            this.nominal = nominal;
        }
    }

    /** Name of the symbolic variable (Green/Z3), derived from uncertainty id and attribute. */
    public final String label;

    public final String uncertaintyId;
    /** Handle of the (first) element (IdAttributeHandleStrategy), as Mode 1 uses it. */
    public final String handle;

    public final String attribute;
    /** Integer sources: domain and nominal value. For real sources the casts of the real fields. */
    public final int min;
    public final int max;
    /** Value in the nominal model state. */
    public final int nominal;

    /** True for a real-valued variable (float or double attributes). */
    public final boolean real;
    /** True if the variable is a shift added to every member's nominal value (grouped annotation). */
    public final boolean offset;
    public final double minReal;
    public final double maxReal;
    /** Nominal value of the variable: the attribute's nominal value, or 0 for a shift. */
    public final double nominalReal;
    public final List<Member> members = new ArrayList<>();

    /** Integer source on one attribute (unchanged since scenario A). */
    public UncertaintySource(String label, String uncertaintyId, String handle, String attribute, int min, int max,
            int nominal) {
        this.label = label;
        this.uncertaintyId = uncertaintyId;
        this.handle = handle;
        this.attribute = attribute;
        this.min = min;
        this.max = max;
        this.nominal = nominal;
        this.real = false;
        this.offset = false;
        this.minReal = min;
        this.maxReal = max;
        this.nominalReal = nominal;
        members.add(new Member(handle, attribute, nominal));
    }

    /** Real source: the value of one attribute ({@code offset == false}) or a shift of all members. */
    public UncertaintySource(String label, String uncertaintyId, List<Member> members, boolean offset, double min,
            double max) {
        this.label = label;
        this.uncertaintyId = uncertaintyId;
        this.handle = members.get(0).handle;
        this.attribute = members.get(0).attribute;
        this.real = true;
        this.offset = offset;
        this.minReal = min;
        this.maxReal = max;
        this.nominalReal = offset ? 0.0 : members.get(0).nominal;
        this.min = (int) Math.floor(min);
        this.max = (int) Math.ceil(max);
        this.nominal = (int) Math.round(nominalReal);
        this.members.addAll(members);
    }

    public boolean hasAlternatives() {
        return minReal < maxReal;
    }

    /** Nominal value of the variable as it appears in inputs (Integer or Double). */
    public Number nominalValue() {
        return real ? (Number) nominalReal : (Number) nominal;
    }

    /** Value that member i takes when the variable has value v. */
    public double memberValue(Member m, double v) {
        return offset ? m.nominal + v : v;
    }

    /**
     * The variable's value in a model state given as "handle#attribute" → value (Mode 1's rows);
     * null if the first member is not in the map (then it is at its nominal value).
     */
    public Double valueIn(java.util.Map<String, Object> values) {
        Object v = values.get(handle + "#" + attribute);
        if (v == null) {
            return null;
        }
        double x = ((Number) v).doubleValue();
        return offset ? x - members.get(0).nominal : x;
    }

    @Override
    public String toString() {
        return real
                ? label + " = " + (offset ? "shift of " + members.size() + " attributes" : handle + "." + attribute)
                        + " in [" + minReal + ", " + maxReal + "], nominal " + nominalReal
                : label + " = " + handle + "." + attribute + " in [" + min + ", " + max + "], nominal " + nominal;
    }
}
