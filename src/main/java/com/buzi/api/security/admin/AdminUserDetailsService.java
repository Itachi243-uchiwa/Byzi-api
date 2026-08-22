package com.buzi.api.security.admin;

import com.buzi.api.domain.Role;
import com.buzi.api.domain.User;
import com.buzi.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Authentification du back-office : seuls les comptes ADMIN disposant d'un mot de passe
 * peuvent se connecter (EPIC-09.1 - "role ADMIN distinct des users").
 * <p>
 * Trois conditions sont exigees, et l'echec de n'importe laquelle produit exactement le meme
 * message : compte inexistant, compte sans role ADMIN, ou compte ADMIN sans mot de passe.
 * Distinguer ces cas indiquerait a un attaquant quels emails existent en base et lesquels
 * sont administrateurs (OWASP A07 - enumeration de comptes).
 */
@Service
@RequiredArgsConstructor
public class AdminUserDetailsService implements UserDetailsService {

    private static final String GENERIC_FAILURE = "Identifiants invalides";

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(GENERIC_FAILURE));

        if (user.getRole() != Role.ADMIN || user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new UsernameNotFoundException(GENERIC_FAILURE);
        }

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + Role.ADMIN.name())))
                .build();
    }
}
