package io.github.viniciusclemos.booking.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The staff logs in with e-mail and password and gets a session cookie (HttpOnly, so a
 * script on the page can't read it). Since the browser sends that cookie by itself,
 * changes also need the CSRF token, which the page reads from the XSRF-TOKEN cookie
 * and sends back in the X-XSRF-TOKEN header. Another site can make the browser send
 * the cookie, but it can't read the token.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filters(HttpSecurity http, SecurityContextRepository contexts) throws Exception {
        http
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers("/api/admin/**").authenticated()
                        .anyRequest().permitAll())
                .securityContext(c -> c.securityContextRepository(contexts))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // clients book and cancel without logging in (the cancel link carries its own
                        // secret), so there's no session to steal there;
                        // the login itself has no session yet either
                        .ignoringRequestMatchers("/api/bookings", "/api/bookings/by-token/**", "/api/auth/login"))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // a page that calls the API wants a 401, not a redirect to a login form
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(out -> out
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.NO_CONTENT.value())));
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService staffDetails(StaffStore staff) {
        return email -> staff.findByEmail(email)
                .map(s -> User.withUsername(s.email()).password(s.passwordHash()).roles("STAFF").build())
                .orElseThrow(() -> new UsernameNotFoundException(email));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /** The token is created lazily; touching it makes sure the cookie goes out with every response. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(request, response);
        }
    }
}
