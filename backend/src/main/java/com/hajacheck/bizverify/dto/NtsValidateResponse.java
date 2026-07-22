package com.hajacheck.bizverify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국세청 진위확인 응답(#596). 판정에 필요한 필드만 화이트리스트로 매핑하고 나머지는 무시한다.
 * <pre>
 * {"data":[{"b_no":"...","valid":"01"|"02","status":{"b_stt_cd":"01"|"02"|"03", ...}}]}
 * </pre>
 * <ul>
 *   <li>{@code valid}: "01" 일치 / "02" 불일치(미등록 포함)</li>
 *   <li>{@code status.b_stt_cd}: "01" 계속사업자 / "02" 휴업 / "03" 폐업 / 미등록 시 빈 값</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NtsValidateResponse(List<ValidatedBusiness> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidatedBusiness(
            String valid,
            Status status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
            @JsonProperty("b_stt_cd") String bSttCd) {
    }
}
