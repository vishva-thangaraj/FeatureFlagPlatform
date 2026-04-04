package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.tenant.entity.UserInvitation;
import com.feature_flag.platform.tenant.enums.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserInvitationRepository extends JpaRepository<UserInvitation, Long> {

    UserInvitation findByEmailAndTenantId(String email, Long tenantId);

    Optional<UserInvitation> findByTokenHash(String tokenHash);

    List<UserInvitation> findByEmailAndStatus(String email, InvitationStatus status);
}