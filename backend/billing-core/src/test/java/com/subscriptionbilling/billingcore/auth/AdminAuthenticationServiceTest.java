package com.subscriptionbilling.billingcore.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthenticationServiceTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Mock
    private AdminTokenIssuer tokenIssuer;

    @Test
    void correctCredentialsAgainstAConfiguredHashIssueAToken() {
        AdminAuthProperties properties = adminProperties("ops-admin", "s3cret!");
        AdminAuthenticationService service = new AdminAuthenticationService(properties, ENCODER, tokenIssuer);
        when(tokenIssuer.issueFor("ops-admin")).thenReturn("minted-token");

        String token = service.authenticate("ops-admin", "s3cret!");

        assertThat(token).isEqualTo("minted-token");
    }

    @Test
    void wrongPasswordAgainstAConfiguredHashIsRejected() {
        AdminAuthProperties properties = adminProperties("ops-admin", "s3cret!");
        AdminAuthenticationService service = new AdminAuthenticationService(properties, ENCODER, tokenIssuer);

        assertThatThrownBy(() -> service.authenticate("ops-admin", "wrong-password"))
                .isInstanceOf(AdminAuthenticationException.class);
    }

    @Test
    void unknownUsernameIsRejectedWithTheSameExceptionAsAWrongPassword() {
        AdminAuthProperties properties = adminProperties("ops-admin", "s3cret!");
        AdminAuthenticationService service = new AdminAuthenticationService(properties, ENCODER, tokenIssuer);

        assertThatThrownBy(() -> service.authenticate("someone-else", "s3cret!"))
                .isInstanceOf(AdminAuthenticationException.class);
    }

    @Test
    void aBlankConfiguredPasswordHashFallsBackToTheDevOnlyDefaultPassword() {
        AdminAuthProperties properties = new AdminAuthProperties();
        properties.setUsername("admin");
        properties.setPasswordHash("");
        AdminAuthenticationService service = new AdminAuthenticationService(properties, ENCODER, tokenIssuer);
        when(tokenIssuer.issueFor("admin")).thenReturn("minted-token");

        String token = service.authenticate("admin", AdminAuthenticationService.DEV_DEFAULT_PASSWORD);

        assertThat(token).isEqualTo("minted-token");
    }

    private static AdminAuthProperties adminProperties(String username, String rawPassword) {
        AdminAuthProperties properties = new AdminAuthProperties();
        properties.setUsername(username);
        properties.setPasswordHash(ENCODER.encode(rawPassword));
        return properties;
    }
}
