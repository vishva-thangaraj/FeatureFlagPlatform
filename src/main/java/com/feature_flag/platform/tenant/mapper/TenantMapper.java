package com.feature_flag.platform.tenant.mapper;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.tenant.dto.RoleResponse;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Component
public class TenantMapper {

    public TenantResponse toTenantResponse(Tenant tenant, Collection<Role> roles) {
        List<RoleResponse> roleResponses = roles.stream()
                .map(r -> new RoleResponse(r.getId(), r.getName()))
                .toList();
        return new TenantResponse(tenant.getId().toString(), tenant.getName(), tenant.getPlan(), roleResponses);
    }

    public TenantResponse toTenantResponse(Membership membership) {
        Tenant tenant = membership.getTenant();
        return toTenantResponse(tenant, membership.getRoles());
    }
}