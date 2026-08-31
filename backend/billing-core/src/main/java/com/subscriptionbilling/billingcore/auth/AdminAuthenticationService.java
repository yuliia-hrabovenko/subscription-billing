package com.subscriptionbilling.billingcore.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Validates {@code POST /api/v1/admin/login}'s credentials against the single
 * configured Admin identity and, on success, issues a bearer token via
 * {@link AdminTokenIssuer}. See {@link AdminAuthProperties}'s Javadoc for why there's
 * no {@code Admin} entity/table behind this.
 */
@Service
public class AdminAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthenticationService.class);

    /**
     * Used only when {@link AdminAuthProperties#getPasswordHash()} is blank — i.e. no
     * {@code ADMIN_PASSWORD_HASH} has been configured. Labeled as a dev-only default
     * for the same reason {@link JwtProperties#getSecret()}'s default is: safe to boot
     * with locally, never appropriate in a real environment. Kept under BCrypt's
     * 72-byte input limit. Public (not just visible in source, like {@code
     * JwtProperties}' default secret already is) so integration tests across modules
     * can log in as the dev-default Admin without duplicating this string.
     */
    public static final String DEV_DEFAULT_PASSWORD = "dev-only-admin-password-override-with-ADMIN_PASSWORD_HASH";

    private final AdminAuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final AdminTokenIssuer tokenIssuer;
    private final String effectivePasswordHash;

    @Autowired
    public AdminAuthenticationService(AdminAuthProperties properties, PasswordEncoder passwordEncoder,
                                       AdminTokenIssuer tokenIssuer) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        if (StringUtils.hasText(properties.getPasswordHash())) {
            this.effectivePasswordHash = properties.getPasswordHash();
        } else {
            log.warn("No ADMIN_PASSWORD_HASH configured; falling back to a dev-only default admin password. "
                    + "Set ADMIN_PASSWORD_HASH (a bcrypt hash) in every real environment.");
            this.effectivePasswordHash = passwordEncoder.encode(DEV_DEFAULT_PASSWORD);
        }
    }

    /**
     * @param username the submitted admin username
     * @param password the submitted admin password (raw, never logged)
     * @return a bearer token identifying the Admin
     * @throws AdminAuthenticationException if the username or password don't match
     */
    public String authenticate(String username, String password) {
        boolean usernameMatches = properties.getUsername().equals(username);
        // Always run the (expensive) hash comparison, even for a username that can't
        // possibly match, so a caller can't distinguish "unknown username" from "wrong
        // password" by response timing.
        boolean passwordMatches = passwordEncoder.matches(password != null ? password : "", effectivePasswordHash);
        if (!usernameMatches || !passwordMatches) {
            throw new AdminAuthenticationException();
        }
        return tokenIssuer.issueFor(username);
    }
}
