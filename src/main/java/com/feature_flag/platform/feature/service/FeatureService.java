package com.feature_flag.platform.feature.service;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ConflictException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.feature.dto.CreateFeatureRequest;
import com.feature_flag.platform.feature.dto.FeatureListResponse;
import com.feature_flag.platform.feature.dto.FeatureResponse;
import com.feature_flag.platform.feature.dto.UpdateFeatureRequest;
import com.feature_flag.platform.feature.entity.Feature;
import com.feature_flag.platform.feature.mapper.FeatureMapper;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FeatureService {
    private final FeatureRepository featureRepository;
    private final TenantRepository tenantRepository;

    public FeatureService(FeatureRepository featureRepository, TenantRepository tenantRepository) {
        this.featureRepository = featureRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public FeatureResponse createFeature(CreateFeatureRequest request, Long tenantId) {
        if (featureRepository.existsByKeyAndTenantId(request.key(), tenantId)) {
            throw new ConflictException(I18N.getMessage(Messages.FEATURE_KEY_ALREADY_EXISTS));
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_TENANT_NOT_FOUND)));

        Feature feature = Feature.builder()
                .key(request.key())
                .description(request.description())
                .tenant(tenant)
                .build();

        featureRepository.save(feature);
        return FeatureMapper.toFeatureResponse(feature);
    }

    public FeatureListResponse getFeaturesList(Long tenantId) {
        List<Feature> features = featureRepository.findAllByTenantId(tenantId);
        List<FeatureResponse> featureResponses = features.stream()
                .map(FeatureMapper::toFeatureResponse)
                .toList();
        return new FeatureListResponse(featureResponses);
    }

    public FeatureResponse getFeature(Long featureId, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        return FeatureMapper.toFeatureResponse(feature);
    }

    @Transactional
    public FeatureResponse updateFeature(Long featureId, UpdateFeatureRequest request, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        feature.setDescription(request.description());
        featureRepository.save(feature);
        return FeatureMapper.toFeatureResponse(feature);
    }

    @Transactional
    public void deleteFeature(Long featureId, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        featureRepository.delete(feature);
    }

    private Feature findByIdAndTenant(Long featureId, Long tenantId) {
        return featureRepository.findById(featureId)
                .filter(f -> f.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_FEATURE_NOT_FOUND)));
    }
}
