package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #2438: the single resolver for the HRmax a day's Effort is scored against.
 *
 * Step 0 of #2438 changes what that HRmax means, and it was resolved by hand at six call sites across the
 * two platforms, two of which had already lost the override (#2460). These cases pin the resolver itself;
 * `Tools/test_effort_hrmax_resolver.py` pins that no call site resolves it by hand.
 *
 * Byte-parity twin of Swift `EffortHRmaxResolverTests`. The case table is the same literal on both sides.
 */
class EffortHRmaxResolverTest {

    private data class Case(val override: Double?, val age: Double, val hrmax: Double?, val source: String)

    private val cases = listOf(
        Case(null, 30.0, 187.0, "tanaka"),
        Case(null, 37.0, 182.1, "tanaka"),
        Case(195.0, 30.0, 195.0, "override"),
        // An override wins without an age: the setting is the user's own number and needs nothing else.
        Case(195.0, 0.0, 195.0, "override"),
        // An override BELOW the formula is still the override, never max(override, tanaka).
        Case(150.0, 80.0, 150.0, "override"),
        // No override and no age: null, which strain() replaces with defaultMaxHR().
        Case(null, 0.0, null, "default"),
        Case(null, -1.0, null, "default"),
    )

    @Test
    fun `every case resolves to its value and its source`() {
        for (c in cases) {
            val (hrmax, source) = StrainScorer.effortHRmax(c.override, c.age)
            assertEquals("source for $c", c.source, source)
            if (c.hrmax == null) assertNull("hrmax for $c", hrmax)
            else assertEquals("hrmax for $c", c.hrmax, hrmax!!, 1e-9)
        }
    }

    /** The resolver and the age formula agree exactly whenever the formula is the answer. */
    @Test
    fun `tanaka is the formula itself, not a rounded copy`() {
        for (age in 1..100) {
            assertEquals(StrainScorer.tanakaHRmax(age.toDouble()), StrainScorer.effortHRmax(null, age.toDouble()).first)
        }
    }
}
