package com.feature_flag.platform.service;

import com.feature_flag.platform.config.I18N;
import com.feature_flag.platform.config.Messages;
import com.feature_flag.platform.entity.Membership;
import com.feature_flag.platform.entity.User;
import com.feature_flag.platform.entity.UserInvitation;
import com.feature_flag.platform.enums.InvitationStatus;
import com.feature_flag.platform.enums.MembershipStatus;
import com.feature_flag.platform.exception.InvalidTokenException;
import com.feature_flag.platform.repository.MembershipRepository;
import com.feature_flag.platform.repository.UserInvitationRepository;
import com.feature_flag.platform.repository.UserRepository;
import com.feature_flag.platform.security.TokenHashUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Service
public class InvitationService {

    private final UserInvitationRepository userInvitationRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final com.feature_flag.platform.repository.RoleRepository roleRepository;

    public InvitationService(UserInvitationRepository userInvitationRepository,
                             UserRepository userRepository,
                             MembershipRepository membershipRepository,
                             com.feature_flag.platform.repository.RoleRepository roleRepository) {
        this.userInvitationRepository = userInvitationRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
    }

    @Transactional
    public InvitationResult acceptInvitation(String rawToken) {
        String tokenHash = TokenHashUtil.sha256(rawToken);

        UserInvitation invitation = userInvitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                        I18N.getMessage(Messages.INVITATION_TOKEN_INVALID)));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    I18N.getMessage(Messages.INVITATION_ALREADY_PROCESSED));
        }

        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            userInvitationRepository.save(invitation);
            throw new InvalidTokenException(
                    I18N.getMessage(Messages.INVITATION_EXPIRED));
        }

        Optional<User> existingUser = userRepository.findByEmail(invitation.getEmail());

        if (existingUser.isPresent()) {
            createMembership(existingUser.get(), invitation);
            invitation.setStatus(InvitationStatus.ACCEPTED);
            userInvitationRepository.save(invitation);
            return new InvitationResult("Invitation accepted", true);
        } else {
            invitation.setStatus(InvitationStatus.VALIDATED);
            userInvitationRepository.save(invitation);
            return new InvitationResult("Please register to complete your invitation", false);
        }
    }

    private void createMembership(User user, UserInvitation invitation) {
        var role = roleRepository.findByName(invitation.getRole().name())
                .orElseThrow();

        Membership membership = Membership.builder()
                .user(user)
                .tenant(invitation.getTenant())
                .roles(Set.of(role))
                .status(MembershipStatus.ACTIVE)
                .build();
        membershipRepository.save(membership);
    }

    public record InvitationResult(String message, boolean registered) {}
}
