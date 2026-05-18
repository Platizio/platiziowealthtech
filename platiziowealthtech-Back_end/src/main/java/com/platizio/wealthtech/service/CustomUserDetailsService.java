package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final DistributorRepository distributorRepository;

    public CustomUserDetailsService(DistributorRepository distributorRepository) {
        this.distributorRepository = distributorRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Distributor distributor = distributorRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Distributor not found: " + email));

        return new AuthenticatedDistributorPrincipal(
                distributor.getId(),
                distributor.getEmail(),
                distributor.getPasswordHash() != null ? distributor.getPasswordHash() : "",
                distributor.getRole(),
                List.of(new SimpleGrantedAuthority("ROLE_" + distributor.getRole().name()))
        );
    }
}
