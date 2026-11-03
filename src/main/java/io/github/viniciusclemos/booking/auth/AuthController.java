package io.github.viniciusclemos.booking.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final LoginAttempts attempts;
    private final StaffStore staff;

    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts,
                          LoginAttempts attempts, StaffStore staff) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.attempts = attempts;
        this.staff = staff;
    }

    public record Credentials(@NotBlank String email, @NotBlank String password) {
    }

    public record Me(String name, String email) {
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody Credentials body, HttpServletRequest request,
                            HttpServletResponse response) {
        Duration wait = attempts.waitFor(body.email());
        if (!wait.isZero() && !wait.isNegative()) {
            long minutes = Math.max(1, (wait.getSeconds() + 59) / 60);
            return error(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong passwords. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
        }

        Authentication result;
        try {
            result = authentication.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.email().trim(), body.password()));
        } catch (AuthenticationException e) {
            attempts.failed(body.email());
            // the same answer for an unknown e-mail and a wrong password, so nobody can
            // find out which e-mails have an account
            return error(HttpStatus.UNAUTHORIZED, "Wrong e-mail or password.");
        }
        attempts.succeeded(body.email());

        // a new session id after logging in, so an id planted before the login is worthless
        if (request.getSession(false) != null) request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.ok(me(result.getName()));
    }

    @GetMapping("/me")
    ResponseEntity<?> me(Authentication current) {
        if (current == null || current instanceof AnonymousAuthenticationToken) {
            return error(HttpStatus.UNAUTHORIZED, "Not logged in.");
        }
        return ResponseEntity.ok(me(current.getName()));
    }

    private Me me(String email) {
        return staff.findByEmail(email).map(s -> new Me(s.name(), s.email())).orElse(new Me(email, email));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message, "problems", List.of()));
    }
}
