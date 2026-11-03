package io.github.viniciusclemos.booking.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first login from OWNER_EMAIL and OWNER_PASSWORD, only while nobody exists yet.
 * There's no default password on purpose: an install someone forgot to configure
 * just has no admin, instead of an admin everyone can guess.
 */
@Component
public class OwnerSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerSetup.class);
    static final int MIN_PASSWORD = 10;

    private final StaffStore staff;
    private final PasswordEncoder encoder;
    private final String name;
    private final String email;
    private final String password;

    public OwnerSetup(StaffStore staff, PasswordEncoder encoder,
                      @Value("${booking.owner.name:Owner}") String name,
                      @Value("${booking.owner.email:}") String email,
                      @Value("${booking.owner.password:}") String password) {
        this.staff = staff;
        this.encoder = encoder;
        this.name = name;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!staff.isEmpty()) return;
        if (email.isBlank() || password.isBlank()) {
            log.warn("No staff login yet. Set OWNER_EMAIL and OWNER_PASSWORD to create the first one.");
            return;
        }
        if (password.length() < MIN_PASSWORD) {
            log.warn("OWNER_PASSWORD needs at least {} characters, the owner login wasn't created.", MIN_PASSWORD);
            return;
        }
        staff.create(name, email, encoder.encode(password));
        log.info("Created the owner login for {}", email);
    }
}
