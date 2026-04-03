package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.tenant.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByTenantId(Long tenantId);
}