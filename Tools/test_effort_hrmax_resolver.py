"""#2438: the HRmax a day's Effort is scored against is resolved in ONE place.

`StrainScorer.effortHRmax` (Swift and Kotlin) answers "manual override, else Tanaka from age, else the
default". Before it existed, that answer was written out by hand at six call sites, and two of them had
silently dropped the override (#2460): today's ring was then scored against a different HRmax than the
day it belonged to, and nothing failed. #2438 step 0 changes what the effective HRmax means, so every
reader must change together, which is only safe if no reader resolves it by itself.

The Swift and Kotlin unit tests pin the resolver. This file pins its readers, which neither of those can
see: the SwiftUI Today views compute Effort in private async methods that no XCTest reaches
(`StrandTests/EffortHRmaxOverrideTests.swift` says so in its own header).

Two assertions, following AGENTS.md ("assert the single resolver" rather than counting call sites):

1. No product source resolves "override, else age formula" by hand.
2. Each known day-Effort reader names the resolver.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]

PRODUCT_ROOTS = (
    "Strand", "StrandiOS", "StrandiOSShared", "StrandiOSWidgets", "NOOPWatch", "NOOPWatchComplications",
    "Packages", "android/app/src/main",
)

# The resolvers themselves, which are where the formula is supposed to be.
RESOLVER_FILES = {
    "Packages/StrandAnalytics/Sources/StrandAnalytics/StrainScorer.swift",
    "android/app/src/main/java/com/noop/analytics/StrainScorer.kt",
}

# The age formula, written either through the helper or as the literal.
FORMULA = re.compile(r"tanakaHRmax\(|tanakaMaxHR\(|hrMaxTanaka\(|208(?:\.0)?\s*-\s*0\.7\s*\*")
# A manual override, under any of the names the two codebases give it.
OVERRIDE = re.compile(r"\b(?:maxHROverride|hrMaxOverride)\b")
# Lines either side of a formula hit that count as the same expression. The hand-written fallbacks this
# replaced spanned at most two lines (Kotlin wraps the `?:` branch onto the next one).
WINDOW = 2

# Known hand-resolved sites that are NOT day Effort, each with the reason it is not routed yet. These
# score WORKOUTS against the rounded `hrMax` (or, in one case, an unrounded literal), so routing them
# through the resolver changes stored scores and needs its own issue and a maintainer decision.
# An entry that no longer matches fails `test_every_exemption_still_matches`, so this list can only shrink.
EXEMPT = {
    # Display / workout HRmax: rounds Tanaka to an Int and returns 208 for an age-less profile.
    ("Strand/Data/Profile.swift", "var hrMax: Int"),
    # Manual-workout rescore. Unrounded here, rounded in its Swift twin (`Double(profile.hrMax)`),
    # so the two platforms already store different values for the same workout.
    ("android/app/src/main/java/com/noop/analytics/IntelligenceEngine.kt", "val hrMax = maxHROverride"),
}

# Every reader that scores a DAY's Effort, and how it must reach the resolver.
DAY_EFFORT_READERS = {
    "Packages/StrandAnalytics/Sources/StrandAnalytics/AnalyticsEngine.swift": "StrainScorer.effortHRmax(",
    "android/app/src/main/java/com/noop/analytics/AnalyticsEngine.kt": "StrainScorer.effortHRmax(",
    "Strand/Data/DayCycleIntelligenceIntegration.swift": "StrainScorer.effortHRmax(",
    "android/app/src/main/java/com/noop/analytics/PhysiologicalStepCycleEngine.kt": "StrainScorer.effortHRmax(",
    "android/app/src/main/java/com/noop/ui/TodayScreen.kt": "StrainScorer.effortHRmax(",
    "Strand/Data/Profile.swift": "StrainScorer.effortHRmax(",
    # The two iOS Today views read it through ProfileStore, which is itself routed (above).
    "Strand/Screens/TodayView.swift": "profile.effortHRmax",
    "Strand/Liquid/LiquidTodayView.swift": "profile.effortHRmax",
}


def _strip_comment(line: str) -> str:
    """Drop a trailing `//` comment so prose that mentions the formula is not a hit."""
    index = line.find("//")
    return line if index < 0 else line[:index]


def _product_sources() -> list[Path]:
    files: list[Path] = []
    for root in PRODUCT_ROOTS:
        base = REPO_ROOT / root
        if not base.exists():
            continue
        for suffix in ("*.swift", "*.kt"):
            files.extend(p for p in base.rglob(suffix) if "/Tests/" not in p.as_posix() and "/test/" not in p.as_posix())
    return sorted(files)


def hand_resolved_sites(text: str) -> list[tuple[int, str]]:
    """Lines where the age formula sits within WINDOW lines of an override: a hand-written fallback."""
    lines = [_strip_comment(line) for line in text.splitlines()]
    hits = []
    for index, line in enumerate(lines):
        if not FORMULA.search(line):
            continue
        lo, hi = max(0, index - WINDOW), min(len(lines), index + WINDOW + 1)
        if any(OVERRIDE.search(lines[j]) for j in range(lo, hi)):
            hits.append((index + 1, text.splitlines()[index].strip()))
    return hits


def _exempt(relative: str, text: str, line_no: int) -> bool:
    lines = text.splitlines()
    lo, hi = max(0, line_no - 1 - WINDOW), min(len(lines), line_no + WINDOW)
    window = "\n".join(lines[lo:hi])
    return any(path == relative and marker in window for path, marker in EXEMPT)


class EffortHRmaxResolverTests(unittest.TestCase):
    def test_no_product_source_resolves_the_effort_hrmax_by_hand(self) -> None:
        offenders = []
        for path in _product_sources():
            relative = path.relative_to(REPO_ROOT).as_posix()
            if relative in RESOLVER_FILES:
                continue
            text = path.read_text(encoding="utf-8")
            for line_no, line in hand_resolved_sites(text):
                if not _exempt(relative, text, line_no):
                    offenders.append(f"{relative}:{line_no}: {line}")
        self.assertEqual(
            offenders, [],
            "override-else-age-formula resolved by hand. Call StrainScorer.effortHRmax instead, so step 0 "
            "of #2438 has one place to change (#2460 is what two such copies did):\n" + "\n".join(offenders),
        )

    def test_every_day_effort_reader_names_the_resolver(self) -> None:
        for relative, needle in DAY_EFFORT_READERS.items():
            with self.subTest(file=relative):
                text = (REPO_ROOT / relative).read_text(encoding="utf-8")
                self.assertIn(needle, text, f"{relative} scores a day's Effort and must read {needle}")

    def test_every_exemption_still_matches(self) -> None:
        """An exemption whose code has moved or been routed must be deleted, not left to excuse a new site."""
        for relative, marker in EXEMPT:
            with self.subTest(file=relative, marker=marker):
                text = (REPO_ROOT / relative).read_text(encoding="utf-8")
                matched = any(
                    _exempt(relative, text, line_no) and marker in "\n".join(
                        text.splitlines()[max(0, line_no - 1 - WINDOW):line_no + WINDOW])
                    for line_no, _ in hand_resolved_sites(text)
                )
                self.assertTrue(matched, f"stale exemption: {relative} no longer hand-resolves at {marker!r}")

    # The detector itself, on fixtures, so a regex change cannot quietly blind the two tests above.

    def test_detects_the_swift_shape_that_caused_2460(self) -> None:
        src = "let effMaxHR = maxHROverride ?? (profile.age > 0 ? StrainScorer.tanakaHRmax(age: profile.age) : nil)"
        self.assertEqual(len(hand_resolved_sites(src)), 1)

    def test_detects_the_wrapped_kotlin_shape(self) -> None:
        src = "val effMaxHR = maxHROverride\n    ?: if (profile.age > 0) StrainScorer.tanakaHRmax(profile.age) else null"
        self.assertEqual(len(hand_resolved_sites(src)), 1)

    def test_detects_a_literal_formula(self) -> None:
        self.assertEqual(len(hand_resolved_sites("val m = maxHROverride ?: (208.0 - 0.7 * age)")), 1)

    def test_ignores_the_formula_alone(self) -> None:
        self.assertEqual(hand_resolved_sites("tanaka: profile.age > 0 ? StrainScorer.tanakaHRmax(age: a) : nil"), [])

    def test_ignores_comments(self) -> None:
        self.assertEqual(hand_resolved_sites("// maxHROverride ?? tanakaHRmax(age:) used to live here"), [])


if __name__ == "__main__":
    unittest.main()
