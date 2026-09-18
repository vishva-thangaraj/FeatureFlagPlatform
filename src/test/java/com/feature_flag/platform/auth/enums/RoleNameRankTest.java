package com.feature_flag.platform.auth.enums;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ranking every grant decision rests on.
 *
 * <p>The trap this pins shut: {@code RoleName} is declared {@code OWNER, ADMIN, MEMBER, VIEWER,
 * SUPER_ADMIN}, so declaration order puts SUPER_ADMIN <em>last</em> while it must rank <em>highest</em>.
 * Anything built on {@code ordinal()} therefore reads SUPER_ADMIN as the least privileged role and lets any
 * caller at all hand it out.
 */
class RoleNameRankTest {

    @Test
    void rankOrdersTheHierarchyHighestFirst() {
        List<RoleName> byRankDescending = List.of(RoleName.values()).stream()
                .sorted(Comparator.comparingInt(RoleName::rank).reversed())
                .toList();

        assertThat(byRankDescending).containsExactly(
                RoleName.SUPER_ADMIN, RoleName.OWNER, RoleName.ADMIN, RoleName.MEMBER, RoleName.VIEWER);
    }

    /** Stated as its own assertion because it is the exact inversion an ordinal comparison introduces. */
    @Test
    void superAdminOutranksOwner_despiteBeingDeclaredLast() {
        assertThat(RoleName.SUPER_ADMIN.ordinal())
                .as("declaration order is the trap, not the ranking")
                .isGreaterThan(RoleName.OWNER.ordinal());

        assertThat(RoleName.SUPER_ADMIN.outranksOrEquals(RoleName.OWNER)).isTrue();
        assertThat(RoleName.OWNER.outranksOrEquals(RoleName.SUPER_ADMIN)).isFalse();
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
        assertThat(RoleName.highestOf(List.of("ADMIN", "SUPER_ADMIN"))).contains(RoleName.SUPER_ADMIN);
    }

    @Test
    void highestOfIgnoresUnknownNamesAndEmptyInput() {
        assertThat(RoleName.highestOf(List.of("ARCHDUKE", "MEMBER"))).contains(RoleName.MEMBER);
        assertThat(RoleName.highestOf(List.of("ARCHDUKE"))).isEqualTo(Optional.empty());
        assertThat(RoleName.highestOf(List.of())).isEqualTo(Optional.empty());
        assertThat(RoleName.highestOf(null)).isEqualTo(Optional.empty());
    }
}
