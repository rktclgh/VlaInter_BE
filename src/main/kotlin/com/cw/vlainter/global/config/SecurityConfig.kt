package com.cw.vlainter.global.config

import com.cw.vlainter.domain.interview.ai.AiRoutingContextClearingFilter
import com.cw.vlainter.global.config.properties.CorsProperties
import com.cw.vlainter.global.security.SuspiciousRequestBlockingFilter
import com.cw.vlainter.global.security.JwtAuthenticationFilter
import com.cw.vlainter.global.security.RestAccessDeniedHandler
import com.cw.vlainter.global.security.RestAuthenticationEntryPoint
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Spring Security 전역 설정.
 *
 * - 세션 저장소는 사용하지 않는 stateless 정책
 * - 인증은 커스텀 JWT 필터로 수행
 * - 인증/문서 경로만 허용하고 나머지는 인증 필요
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val suspiciousRequestBlockingFilter: SuspiciousRequestBlockingFilter,
    private val aiRoutingContextClearingFilter: AiRoutingContextClearingFilter,
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val corsProperties: CorsProperties,
    private val restAuthenticationEntryPoint: RestAuthenticationEntryPoint,
    private val restAccessDeniedHandler: RestAccessDeniedHandler,
    @Value("\${app.docs.enabled:false}")
    private val docsEnabled: Boolean
) {
    /**
     * 회원 비밀번호 검증에 사용할 PasswordEncoder.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /**
     * HTTP 보안 정책을 정의한다.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(restAuthenticationEntryPoint)
                it.accessDeniedHandler(restAccessDeniedHandler)
            }
            .authorizeHttpRequests {
                var registry = it.requestMatchers(*PUBLIC_API_PATHS).permitAll()
                registry = registry.requestMatchers("/actuator/health").permitAll()
                registry = registry.requestMatchers(HttpMethod.GET, "/api/site/patch-notes").permitAll()
                if (docsEnabled) {
                    registry = registry.requestMatchers(*PUBLIC_DOCS_PATHS).permitAll()
                }
                registry
                    .requestMatchers(HttpMethod.POST, "/api/payments/portone/webhook").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/payments/portone/callback").permitAll()
                    // FE 라우트 추가 시 아래 PUBLIC_FRONTEND_ROUTES/PUBLIC_STATIC_PATHS를 함께 갱신한다.
                    .requestMatchers(*PUBLIC_FRONTEND_ROUTES, *PUBLIC_STATIC_PATHS).permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll()
            }
            .addFilterBefore(suspiciousRequestBlockingFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(aiRoutingContextClearingFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    /**
     * HttpOnly 쿠키 기반 cross-origin 인증을 위한 CORS 설정.
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = corsProperties.allowedOrigins
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    private companion object {
        val PUBLIC_API_PATHS = arrayOf(
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/kakao/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/email-verification/send",
            "/api/auth/email-verification/verify",
            "/api/auth/password/temporary"
        )

        val PUBLIC_DOCS_PATHS = arrayOf(
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
        )

        // SPA 엔트리로 직접 접근 가능한 FE 페이지 화이트리스트
        val PUBLIC_FRONTEND_ROUTES = arrayOf(
            "/",
            "/index.html",
            "/login",
            "/join",
            "/password/forgot",
            "/auth/kakao/callback",
            "/content/**",
            "/errors/403",
            "/errors/404"
        )

        val PUBLIC_STATIC_PATHS = arrayOf(
            "/error/**",
            "/assets/**",
            "/favicon.ico",
            "/favicon.png",
            "/social-preview.png",
            "/vite.svg",
            "/icon/**"
        )
    }
}
