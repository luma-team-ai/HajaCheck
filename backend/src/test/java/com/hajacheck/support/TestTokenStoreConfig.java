package com.hajacheck.support;

import com.hajacheck.auth.support.TokenStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * test 프로파일에서 in-memory TokenStore 빈을 제공한다.
 * RedisTokenStore 는 @Profile("!test") 로 test 에서 뜨지 않으므로(RedisAutoConfiguration 제외),
 * 서비스가 요구하는 TokenStore 를 여기서 채운다.
 *
 * <p>plain @Configuration + @Profile("test") 로 두어 @SpringBootTest 컴포넌트 스캔에 자동 포함되게 한다
 * (모든 통합 테스트가 별도 @Import 없이 이 fake 를 얻음). @DataJpaTest 슬라이스는 스캔에 걸리지 않는다.
 */
@Configuration
@Profile("test")
public class TestTokenStoreConfig {

    @Bean
    public TokenStore tokenStore() {
        return new InMemoryTokenStore();
    }
}
