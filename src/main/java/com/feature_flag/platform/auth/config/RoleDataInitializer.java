package com.feature_flag.platform.auth.config;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.repository.RoleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
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
        // Derived from the enum rather than restated, so the table cannot drift from the roles the code
        // actually recognises. The hand-written list previously outlived SUPER_ADMIN's removal from
        // RoleName and would have kept seeding a role nothing could grant or check.
        List<String> roleNames = Arrays.stream(RoleName.values()).map(RoleName::name).toList();

        for (String roleName : roleNames) {
            if (roleRepository.findByName(roleName).isEmpty()) {
                roleRepository.save(Role.builder().name(roleName).build());
            }
        }
    }
}