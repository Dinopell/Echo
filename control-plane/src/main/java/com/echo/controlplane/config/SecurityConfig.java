package com.echo.controlplane.config;

import com.echo.controlplane.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 安全策略配置
 *
 * <p>当 echo.security.jwt.enabled=true 时：
 * <ul>
 *   <li>公开：/actuator/health、/api/callback/**、/api/v1/auth/**</li>
 *   <li>受保护：其余 /api/** 需携带 Bearer JWT</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtProperties jwtProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/actuator/health", "/actuator/info").permitAll();
                auth.requestMatchers("/api/callback/**").permitAll();
                auth.requestMatchers("/api/v1/auth/**").permitAll();

                if (jwtProperties.isEnabled()) {
                    auth.requestMatchers("/api/**").authenticated();
                    auth.anyRequest().authenticated();
                } else {
                    auth.requestMatchers("/api/**").permitAll();
                    auth.anyRequest().permitAll();
                }
            })
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
