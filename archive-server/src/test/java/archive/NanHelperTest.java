package archive;

import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
public class NanHelperTest {

    @Test
    void ensureNonNaNDoubleZeroesNaNAndPassesFiniteAndInfinite() {
        assertEquals(0.0D, NanHelper.ensureNonNaN(Double.NaN));
        assertEquals(42.5D, NanHelper.ensureNonNaN(42.5D));
        assertEquals(-1.0D, NanHelper.ensureNonNaN(-1.0D));
        // Infinity is NOT NaN; ensureNonNaN passes it through unchanged.
        assertEquals(Double.POSITIVE_INFINITY, NanHelper.ensureNonNaN(Double.POSITIVE_INFINITY));
    }

    @Test
    void ensureNonNaNFloatZeroesNaNAndPassesFiniteAndInfinite() {
        assertEquals(0.0F, NanHelper.ensureNonNaN(Float.NaN));
        assertEquals(7.25F, NanHelper.ensureNonNaN(7.25F));
        assertEquals(Float.NEGATIVE_INFINITY, NanHelper.ensureNonNaN(Float.NEGATIVE_INFINITY));
    }

    @Test
    void isInvalidDoubleFlagsNaNAndInfinityAnywhereInVarargs() {
        assertTrue(NanHelper.isInvalid(Double.NaN));
        assertTrue(NanHelper.isInvalid(1.0D, Double.POSITIVE_INFINITY));
        assertTrue(NanHelper.isInvalid(1.0D, 2.0D, Double.NEGATIVE_INFINITY));
        assertFalse(NanHelper.isInvalid(0.0D, -5.5D, Double.MAX_VALUE));
        // Bare isInvalid() would be ambiguous between the overloads.
        assertFalse(NanHelper.isInvalid(new double[0]));
    }

    @Test
    void isInvalidFloatFlagsNaNAndInfinityAnywhereInVarargs() {
        assertTrue(NanHelper.isInvalid(Float.NaN));
        assertTrue(NanHelper.isInvalid(1.0F, Float.POSITIVE_INFINITY));
        assertFalse(NanHelper.isInvalid(0.0F, Float.MIN_VALUE));
        assertFalse(NanHelper.isInvalid(new float[0]));
    }
}
