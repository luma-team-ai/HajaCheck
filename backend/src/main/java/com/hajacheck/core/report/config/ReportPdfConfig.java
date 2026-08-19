package com.hajacheck.core.report.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 보고서 PDF 저장 @ConfigurationProperties 등록(스캔 미사용, 명시 등록) — MediaConfig 패턴과 동일.
 *
 * <p>보고서 PDF는 시설물 하자 정보를 담은 민감문서라 정적 리소스 핸들러로 직접 서빙하지 않는다
 * (#455 P2-1 — 정적 서빙은 컨트롤러를 거치지 않아 소유권 검증이 적용되지 않고, 게다가 실제 운영
 * 배포 프로파일이 'docker'라 관례적인 @Profile("!prod") 게이트도 무력하다). 대신
 * {@code ReportController#downloadPdf}가 소유권 검증(IDOR 방지) 후 {@code ReportPdfStorage#load}로
 * 파일을 읽어 스트리밍한다.
 *
 * <p>{@link ReportPdfCleanupProperties}(#1680)는 {@code OrphanReportPdfCleanupScheduler}의 유예기간
 * 설정 — 저장소 자체 설정과 관심사가 달라 별도 프로퍼티 클래스지만, 같은 report-pdf 도메인이라 이
 * Config에서 함께 등록한다.
 */
@Configuration
@EnableConfigurationProperties({ReportPdfStorageProperties.class, ReportPdfCleanupProperties.class})
public class ReportPdfConfig {
}
