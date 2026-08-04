package com.hajacheck.auth.service;

import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 사업자등록증을 정적 리소스가 아닌 회사 스코프가 검증된 응답으로만 반환한다. */
@Service
@RequiredArgsConstructor
public class BusinessLicenseFileService {

    private final CompanyRepository companyRepository;
    private final CompanyScopeGuard companyScopeGuard;
    private final FileStorageService fileStorage;
    private final FileStorageProperties fileStorageProperties;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LicenseFile load(Long companyId, Long userId, String requestedStorageKey) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));

        String normalizedRequestedStorageKey = normalizeCapturedStorageKey(requestedStorageKey);
        String expectedStorageKey = extractStorageKey(company.getBusinessRegistrationFileUrl());
        if (!isSafeStorageKey(normalizedRequestedStorageKey)
                || !expectedStorageKey.equals(normalizedRequestedStorageKey)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }

        byte[] content = fileStorage.read(normalizedRequestedStorageKey);
        return new LicenseFile(content, contentType(normalizedRequestedStorageKey),
                extension(normalizedRequestedStorageKey));
    }

    /** Spring의 capture-the-rest 경로 변수에서 보존되는 선행 slash 하나만 제거한다. */
    private String normalizeCapturedStorageKey(String storageKey) {
        if (storageKey != null && storageKey.startsWith("/")) {
            return storageKey.substring(1);
        }
        return storageKey;
    }

    private String extractStorageKey(String storedUrl) {
        if (storedUrl == null || storedUrl.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        String basePath = fileStorageProperties.getBaseUrlPath();
        if (!basePath.startsWith("/")) {
            basePath = "/" + basePath;
        }
        while (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String prefix = basePath + "/";
        if (!storedUrl.startsWith(prefix)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        String storageKey = storedUrl.substring(prefix.length());
        if (!isSafeStorageKey(storageKey)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return storageKey;
    }

    private boolean isSafeStorageKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()
                || storageKey.indexOf('\\') >= 0 || storageKey.indexOf('\0') >= 0
                || storageKey.startsWith("/") || storageKey.contains("%")) {
            return false;
        }
        if (!storageKey.startsWith("business-registration/")) {
            return false;
        }
        for (String segment : storageKey.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private String contentType(String storageKey) {
        return switch (extension(storageKey)) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private String extension(String storageKey) {
        int dot = storageKey.lastIndexOf('.');
        return dot < 0 ? "" : storageKey.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    public record LicenseFile(byte[] content, String contentType, String extension) {
    }
}
