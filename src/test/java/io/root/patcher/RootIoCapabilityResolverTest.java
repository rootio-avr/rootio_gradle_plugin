package io.root.patcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.root.patcher.RootIoCapabilityResolver.Candidate.module;
import static io.root.patcher.RootIoCapabilityResolver.Candidate.nonModule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RootIoCapabilityResolver}. Branches are exercised in
 * isolation — no Gradle types mocked. {@link RootIoPatcherPlugin} adapts real
 * Gradle candidates to the simple records used here; the F-series functional
 * tests cover the wiring end-to-end against real Gradle resolution.
 */
class RootIoCapabilityResolverTest {

    @Test
    void r1_noPatched_defers() {
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("com.example", "1.0"),
            module("com.example", "1.1")));
        assertFalse(decision.isSelect());
    }

    @Test
    void r2_customerBug_sameVersionTie_picksPatched() {
        // The exact scenario the customer reported: patched + BOM-pinned upstream both 2.18.2.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.1"),
            module("com.fasterxml.jackson.core", "2.18.2")));
        assertTrue(decision.isSelect());
        assertEquals(0, decision.selectIndex);
    }

    @Test
    void r3_crossVersion_BOMUpgrade_defers() {
        // F2-style: patched 1.1.3 alongside BOM-supplied 1.5.8. Defer so
        // selectHighestVersion picks 1.5.8.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.ch.qos.logback", "1.1.3-root.io.1"),
            module("ch.qos.logback", "1.5.8")));
        assertFalse(decision.isSelect());
    }

    @Test
    void r4_threeWay_higherUpstreamPresent_defers() {
        // Patched 2.18.2 + upstream 2.18.2 + upstream 2.18.3 — no silent
        // downgrade. Defer; selectHighestVersion picks 2.18.3.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.1"),
            module("com.fasterxml.jackson.core", "2.18.2"),
            module("com.fasterxml.jackson.core", "2.18.3")));
        assertFalse(decision.isSelect());
    }

    @Test
    void r5_threeWay_allSameVersion_picksPatched() {
        // Patched 2.18.2 + two upstream coords both at 2.18.2 (e.g., a fork
        // republished at the same coord). All tied at base — pick patched.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.1"),
            module("com.fasterxml.jackson.core", "2.18.2"),
            module("com.fasterxml.jackson.core", "2.18.2")));
        assertTrue(decision.isSelect());
        assertEquals(0, decision.selectIndex);
    }

    @Test
    void r6_nonModuleCandidate_defers() {
        // A project/file candidate alongside the patched coord — we cannot
        // reason about its version. Defer to selectHighestVersion.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.1"),
            nonModule()));
        assertFalse(decision.isSelect());
    }

    @Test
    void r7_multiplePatched_defers() {
        // Defensive: Gradle's intra-module conflict resolution should collapse
        // multiple io.root.* coords before capability resolution fires. If it
        // does not, defer rather than guess.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.1"),
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.2"),
            module("com.fasterxml.jackson.core", "2.18.2")));
        assertFalse(decision.isSelect());
    }

    @Test
    void r8_rootIoGroupWithoutSuffix_notCountedAsPatched() {
        // io.root.* group but no -root.io.N suffix — not a patcher-minted coord.
        // No real patched candidate → defer.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.com.example", "1.0"),
            module("com.example", "1.0")));
        assertFalse(decision.isSelect());
    }

    @Test
    void r9_qualifierUpstream_defers() {
        // Patched 2.18.2 + upstream 2.18.2-rc1. String equality fails → defer.
        // Gradle's comparator inside selectHighestVersion ranks qualifier-bearing
        // pre-releases correctly.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.1"),
            module("com.fasterxml.jackson.core", "2.18.2-rc1")));
        assertFalse(decision.isSelect());
    }

    @Test
    void r10_emptyCandidates_defers() {
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of());
        assertFalse(decision.isSelect());
    }

    @Test
    void r11_doubleDigitSuffix_picksPatched() {
        // Verify the ROOT_IO_SUFFIX regex handles \\d+ (not just \\d).
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.10"),
            module("com.fasterxml.jackson.core", "2.18.2")));
        assertTrue(decision.isSelect());
        assertEquals(0, decision.selectIndex);
    }

    @Test
    void r12_patchedAtIndexTwo_selectsCorrectIndex() {
        // Algorithm must return the index in the original list, not a filtered position.
        RootIoCapabilityResolver.Decision decision = RootIoCapabilityResolver.decide(List.of(
            module("com.fasterxml.jackson.core", "2.18.2"),
            module("com.fasterxml.jackson.core", "2.18.2"),
            module("io.root.com.fasterxml.jackson.core", "2.18.2-root.io.1")));
        assertTrue(decision.isSelect());
        assertEquals(2, decision.selectIndex);
    }
}
