package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.Env;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnvRepository extends JpaRepository<Env, Long> {

    Optional<Env> findByNameAndTenantId(String name, Long tenantId);

    List<Env> findAllByTenantId(Long tenantId);

    boolean existsByNameAndTenantId(String name, Long tenantId);
}