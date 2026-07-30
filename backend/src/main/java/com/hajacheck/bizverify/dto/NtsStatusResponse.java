package com.hajacheck.bizverify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국세청 사업자등록상태조회 응답(#648). 판정에 필요한 필드만 화이트리스트로 매핑하고 나머지는 무시한다.
 * <pre>
 * {"data":[{"b_no":"...","b_stt_cd":"01"|"02"|"03"|"","tax_type":"..."}]}
 * </pre>
 * <ul>
 *   <li>{@code b_stt_cd}: "01" 계속사업자 / "02" 휴업 / "03" 폐업 / 미등록 시 빈 값</li>
 *   <li>{@code tax_type}: 미등록 사업자번호는 "국세청에 등록되지 않은 사업자등록번호입니다" 류의 안내
 *       문자열이 담긴다({@code NtsVerificationOutcome#NOT_REGISTERED} 판정 근거) — {@code validate}
 *       응답(valid 02)과 달리 "미등록"과 "등록됐으나 이름/개업일 불일치"를 구분할 수 있는 유일한 신호.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NtsStatusResponse(List<BusinessStatus> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BusinessStatus(
            @JsonProperty("b_no") String bNo,
            @JsonProperty("b_stt_cd") String bSttCd,
            @JsonProperty("tax_type") String taxType) {
    }
}
