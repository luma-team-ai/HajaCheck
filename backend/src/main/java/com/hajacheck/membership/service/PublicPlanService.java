package com.hajacheck.membership.service;

import com.hajacheck.membership.dto.PublicPlanCatalogResponse;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.repository.PlanRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 랜딩페이지/공개 요금제 카탈로그 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PublicPlanService {

    private final PlanRepository planRepository;

    public PublicPlanCatalogResponse getPublicPlans() {
        List<Plan> plans = planRepository.findAll();
        plans.sort(Comparator.comparing(Plan::getId));
        return PublicPlanCatalogResponse.from(plans);
    }
}
