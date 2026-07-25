package com.hajacheck.mypage.dto;

import com.hajacheck.core.defect.entity.DefectGrade;

/**
 * 마이페이지 "내 보고서" 카드의 등급 dots 신호등 색(#844) — {@link DefectGrade}(A~E)를 3색으로 축약한다
 * (handoff §2-3 계약: E·D→RED, C→ORANGE, B·A→GREEN).
 */
public enum ReportGradeDotColor {
    RED,
    ORANGE,
    GREEN;

    public static ReportGradeDotColor from(DefectGrade grade) {
        return switch (grade) {
            case E, D -> RED;
            case C -> ORANGE;
            case B, A -> GREEN;
        };
    }
}
