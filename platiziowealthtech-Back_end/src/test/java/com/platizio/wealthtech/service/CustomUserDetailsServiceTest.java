package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.repository.DistributorRepository;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

class CustomUserDetailsServiceTest {

    @Test
    void nonAdminDistributorReceivesDistributorAliasAuthority() {
        UserDetails userDetails = new CustomUserDetailsService(repository(DistributorRole.SUB_DISTRIBUTOR))
                .loadUserByUsername("sub@example.com");

        assertThat(userDetails.getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("ROLE_SUB_DISTRIBUTOR", "ROLE_DISTRIBUTOR");
    }

    @Test
    void adminDoesNotReceiveDistributorAliasAuthority() {
        UserDetails userDetails = new CustomUserDetailsService(repository(DistributorRole.ADMIN))
                .loadUserByUsername("admin@example.com");

        assertThat(userDetails.getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("ROLE_ADMIN")
                .doesNotContain("ROLE_DISTRIBUTOR");
    }

    private DistributorRepository repository(DistributorRole role) {
        Distributor distributor = new Distributor();
        distributor.setRole(role);
        distributor.setEmail(role.name().toLowerCase() + "@example.com");
        distributor.setPasswordHash("password");
        return (DistributorRepository) Proxy.newProxyInstance(
                DistributorRepository.class.getClassLoader(),
                new Class<?>[]{DistributorRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByEmail" -> Optional.of(distributor);
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }
}
