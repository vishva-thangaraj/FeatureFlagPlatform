package com.feature_flag.platform.auth.config;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.repository.RoleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class RoleDataInitializer implements ApplicationRunner {

    private final RoleRepository roleRepository;

    public RoleDataInitializer(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String> roleNames = List.of(
                RoleName.OWNER.name(),
                RoleName.ADMIN.name(),
                RoleName.MEMBER.name(),
                RoleName.VIEWER.name(),
                RoleName.SUPER_ADMIN.name()
        );

        for (String roleName : roleNames) {
            if (roleRepository.findByName(roleName).isEmpty()) {
                roleRepository.save(Role.builder().name(roleName).build());
            }
        }
    }
}