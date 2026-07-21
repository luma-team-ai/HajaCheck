package com.hajacheck.core.report.entity;

/**
 * {@link GroundingCheckResult#passed}/{@code failed}은 package-private(도메인 캡슐화)라 다른 패키지의
 * 테스트(예: ReportServiceTest)에서 "이미 grounding을 통과한 Report" 픽스처를 만들 때 필요하다.
 * ReportTest.java는 건드리지 않고, 같은 패키지에서 접근을 허용하는 얇은 테스트 전용 팩토리로 분리한다.
 */
public final class GroundingCheckResultTestFactory {

    private GroundingCheckResultTestFactory() {
    }

    public static GroundingCheckResult passed(GroundingCheckTarget target, String warnings) {
        return GroundingCheckResult.passed(target, warnings);
    }
}
