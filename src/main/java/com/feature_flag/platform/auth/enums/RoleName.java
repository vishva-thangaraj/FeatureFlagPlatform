package com.feature_flag.platform.auth.enums;

import java.util.Collection;
import java.util.Optional;

/**
 * The roles a membership can hold, each carrying its explicit position in the privilege hierarchy.
 *
 * <p><b>The rank is a constructor argument on purpose — never use {@link #ordinal()}.</b> Declaration order
 * here is historical: {@code SUPER_ADMIN} was appended last (moving it would silently rewrite the meaning of
 * any persisted ordinal), yet it is the <em>highest</em> role. An ordinal-based comparison therefore inverts
 * the top of the hierarchy and reads SUPER_ADMIN as the least privileged role, which would let any caller
 * grant it. Because the rank is a constructor argument, the ranking is total over this enum by construction:
 * a role added later cannot compile without declaring where it sits, so it can never default into
 * "grantable by anyone".
 *
 * <p>The numbers are spaced so a new role can be slotted between two existing ones without renumbering.
 * Only their order is meaningful.
 */
public enum RoleName {
    OWNER(80),
    ADMIN(60),
    MEMBER(40),
    VIEWER(20),
    SUPER_ADMIN(100);

    private final int rank;

    RoleName(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** True when this role sits at or above {@code other} in the hierarchy. */
    public boolean outranksOrEquals(RoleName other) {
        return this.rank >= other.rank;
    }

    /**
     * Parses a role name against this enum. Deliberately exact-match: {@code RoleName.valueOf} throws
     * {@link IllegalArgumentException} (an unhandled 500) for anything unknown, including a merely
     * differently-cased name that a case-insensitive database collation had already waved through.
     */
    public static Optional<RoleName> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (RoleName roleName : values()) {
            if (roleName.name().equals(name)) {
                return Optional.of(roleName);
            }
        }
        return Optional.empty();
    }

    /**
     * The highest-ranked role among {@code names}, ignoring any name that is not a known role.
     * Empty when the caller holds no recognisable role at all.
     */
    public static Optional<RoleName> highestOf(Collection<String> names) {
        if (names == null) {
            return Optional.empty();
        }
        return names.stream()
                .map(RoleName::parse)
                .flatMap(Optional::stream)
                .max((a, b) -> Integer.compare(a.rank, b.rank));
    }
}
