import XCTest
@testable import StrandAnalytics

/// #2438: the single resolver for the HRmax a day's Effort is scored against.
///
/// Step 0 of #2438 changes what that HRmax means, and it was resolved by hand at six call sites across
/// the two platforms, two of which had already lost the override (#2460). These cases pin the resolver
/// itself; `Tools/test_effort_hrmax_resolver.py` pins that no call site resolves it by hand.
///
/// Byte-parity twin of Kotlin `EffortHRmaxResolverTest`. The case table is the same literal on both sides.
final class EffortHRmaxResolverTests: XCTestCase {

    private struct Case { let override: Double?; let age: Double; let hrmax: Double?; let source: String }

    private let cases: [Case] = [
        Case(override: nil, age: 30.0, hrmax: 187.0, source: "tanaka"),
        Case(override: nil, age: 37.0, hrmax: 182.1, source: "tanaka"),
        Case(override: 195.0, age: 30.0, hrmax: 195.0, source: "override"),
        // An override wins without an age: the setting is the user's own number and needs nothing else.
        Case(override: 195.0, age: 0.0, hrmax: 195.0, source: "override"),
        // An override BELOW the formula is still the override, never max(override, tanaka).
        Case(override: 150.0, age: 80.0, hrmax: 150.0, source: "override"),
        // No override and no age: nil, which strain() replaces with defaultMaxHR().
        Case(override: nil, age: 0.0, hrmax: nil, source: "default"),
        Case(override: nil, age: -1.0, hrmax: nil, source: "default"),
    ]

    func testEveryCaseResolvesToItsValueAndItsSource() {
        for c in cases {
            let r = StrainScorer.effortHRmax(override: c.override, age: c.age)
            XCTAssertEqual(r.source, c.source, "source for \(c)")
            if let want = c.hrmax {
                XCTAssertEqual(r.hrmax ?? -1, want, accuracy: 1e-9, "hrmax for \(c)")
            } else {
                XCTAssertNil(r.hrmax, "hrmax for \(c)")
            }
        }
    }

    /// The resolver and the age formula agree exactly whenever the formula is the answer.
    func testTanakaIsTheFormulaItselfNotARoundedCopy() {
        for age in 1...100 {
            XCTAssertEqual(StrainScorer.effortHRmax(override: nil, age: Double(age)).hrmax,
                           StrainScorer.tanakaHRmax(age: Double(age)))
        }
    }
}
