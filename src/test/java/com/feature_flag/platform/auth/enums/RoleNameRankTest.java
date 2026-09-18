package com.feature_flag.platform.auth.enums;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ranking every grant decision rests on.
 *
 * <p>The trap this pins shut: declaration order is not privilege order. {@code RoleName} is declared
 * {@code OWNER, ADMIN, MEMBER, VIEWER}, so ordinal runs <em>opposite</em> to rank — OWNER is first and
 * highest, VIEWER is last and lowest. Anything built on {@code ordinal()} therefore concludes that VIEWER
 * outranks OWNER and waves through every escalation the ceiling exists to stop. The enum previously also
 * ended with SUPER_ADMIN, which ranked highest while sorting last, so the two orders have never agreed in
 * either direction.
 */
class RoleNameRankTest {

    @Test
    void rankOrdersTheHierarchyHighestFirst() {
        List<RoleName> byRankDescending = List.of(RoleName.values()).stream()
                .sorted(Comparator.comparingInt(RoleName::rank).reversed())
                .toList();

        assertThat(byRankDescending).containsExactly(
                RoleName.OWNER, RoleName.ADMIN, RoleName.MEMBER, RoleName.VIEWER);
    }

    /**
     * Stated as its own assertion because it is the exact inversion an ordinal comparison introduces:
     * OWNER sorts <em>before</em> VIEWER by declaration yet must outrank it, so {@code ordinal()} gets
     * this backwards. If someone ever "simplifies" the rank away, this fails.
     */
    @Test
    void ownerOutranksViewer_despiteSortingFirstByDeclaration() {
        assertThat(RoleName.OWNER.ordinal())
                .as("declaration order is the trap, not the ranking")
                .isLessThan(RoleName.VIEWER.ordinal());

        assertThat(RoleName.OWNER.outranksOrEquals(RoleName.VIEWER)).isTrue();
        assertThat(RoleName.VIEWER.outranksOrEquals(RoleName.OWNER)).isFalse();
    }

    /** SUPER_ADMIN was removed; nothing may quietly reintroduce a role the grant ceiling never sees. */
    @Test
    void superAdminIsGone() {
        assertThat(RoleName.parse("SUPER_ADMIN")).isEmpty();
        assertThat(List.of(RoleName.values())).extracting(Enum::name).doesNotContain("SUPER_ADMIN");
    }

    @Test
    void everyRoleHasADistinctRank() {
        assertThat(List.of(RoleName.values()).stream().map(RoleName::rank).distinct().count())
                .as("a rank shared by two roles would make the ceiling ambiguous")
                .isEqualTo(RoleName.values().length);
    }

    @Test
    void aRoleOutranksOrEqualsItself() {
        for (RoleName role : RoleName.values()) {
            assertThat(role.outranksOrEquals(role)).isTrue();
        }
    }

    @Test
    void parseIsExactMatchAndNeverThrows() {
        assertThat(RoleName.parse("ADMIN")).contains(RoleName.ADMIN);
        assertThat(RoleName.parse("admin")).isEmpty();
        assertThat(RoleName.parse("Admin")).isEmpty();
        assertThat(RoleName.parse("ARCHDUKE")).isEmpty();
        assertThat(RoleName.parse("")).isEmpty();
        assertThat(RoleName.parse(null)).isEmpty();
    }

    @Test
    void highestOfPicksTheStrongestRoleHeld() {
        assertThat(RoleName.highestOf(List.of("VIEWER", "OWNER", "MEMBER"))).contains(RoleName.OWNER);
        assertThat(RoleName.highestOf(List.of("VIEWER"))).contains(RoleName.VIEWER);
        assertThat(RoleName.highestOf(List.of("VIEWER", "OWNER"))).contains(RoleName.OWNER);
        // An unrecognised name must not become the highest role by accident.
        assertThat(RoleName.highestOf(List.of("ADMIN", "SUPER_ADMIN"))).contains(RoleName.ADMIN);
    }

    @Test
    void highestOfIgnoresUnknownNamesAndEmptyInput() {
        assertThat(RoleName.highestOf(List.of("ARCHDUKE", "MEMBER"))).contains(RoleName.MEMBER);
        assertThat(RoleName.highestOf(List.of("ARCHDUKE"))).isEqualTo(Optional.empty());
        assertThat(RoleName.highestOf(List.of())).isEqualTo(Optional.empty());
        assertThat(RoleName.highestOf(null)).isEqualTo(Optional.empty());
    }
}
