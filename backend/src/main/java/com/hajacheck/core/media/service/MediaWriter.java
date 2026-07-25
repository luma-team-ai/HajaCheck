package com.hajacheck.core.media.service;

import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.repository.MediaRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Media 원자 저장 전담 — 별도 빈으로 분리해 self-invocation 회피(CompanyAccountWriter 와 동일한 이유:
 * 같은 클래스 내부 호출은 @Transactional 프록시가 안 걸리므로, 파일 IO(트랜잭션 밖)를 마친 MediaService가
 * 이 빈을 호출해야 진짜 새 트랜잭션이 열린다). unique/FK 위반은 여기서 그대로 전파되고, 호출부가
 * 저장한 파일들의 보상삭제를 담당한다.
 */
@Component
@RequiredArgsConstructor
public class MediaWriter {

    private final MediaRepository mediaRepository;

    @Transactional
    public List<Media> saveAll(List<Media> mediaList) {
        return mediaRepository.saveAll(mediaList);
    }
}
