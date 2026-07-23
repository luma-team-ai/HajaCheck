package com.hajacheck.core.defect.service;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.repository.DefectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defect 원자 저장 전담(dev-05-04) — MediaWriter와 동일한 이유로 별도 빈 분리(self-invocation 회피).
 * InspectionAnalysisService가 이미지 1장의 FastAPI 호출(트랜잭션 밖 네트워크 IO)을 마친 뒤 이 빈을
 * 호출해 그 이미지의 탐지 결과만 짧은 트랜잭션으로 커밋한다 — 회차 전체를 한 트랜잭션으로 묶으면
 * 이미지 수십 장의 네트워크 호출 동안 DB 커넥션을 붙잡아두게 된다.
 */
@Component
@RequiredArgsConstructor
public class DefectWriter {

    private final DefectRepository defectRepository;

    @Transactional
    public List<Defect> saveAll(List<Defect> defects) {
        return defectRepository.saveAll(defects);
    }
}
