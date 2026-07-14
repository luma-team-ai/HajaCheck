package com.hajacheck.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CookieCsrfTokenRepository 는 지연(deferred) 토큰이라 실제로 값을 읽어야 XSRF-TOKEN 쿠키가 내려간다.
 * 매 요청에서 토큰을 강제 로드해 SPA(axios)가 double-submit 용 쿠키를 받도록 한다.
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // getToken() 호출이 지연 토큰을 렌더링 → CookieCsrfTokenRepository 가 응답에 쿠키를 심는다.
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
