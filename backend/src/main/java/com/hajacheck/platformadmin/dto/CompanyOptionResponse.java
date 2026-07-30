package com.hajacheck.platformadmin.dto;

import com.hajacheck.auth.entity.Company;

/** 사용자 등록 모달의 기업명 selectbox 옵션(#576, PR #626 CompanyOption 대응). */
public record CompanyOptionResponse(Long id, String name) {

    public static CompanyOptionResponse from(Company company) {
        return new CompanyOptionResponse(company.getId(), company.getName());
    }
}
