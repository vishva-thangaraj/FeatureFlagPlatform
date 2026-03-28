package com.feature_flag.platform.repository;

import com.feature_flag.platform.entity.UserInvitation;
import com.feature_flag.platform.enums.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserInvitationRepository extends JpaRepository<UserInvitation, Long> {

    UserInvitation findByEmailAndTenantId(String email, Long tenantId);

    Optional<UserInvitation> findByTokenHash(String tokenHash);

    List<UserInvitation> findByEmailAndStatus(String email, InvitationStatus status);
}
