package com.byzi.api.security.admin;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Authentification du back-office : seuls les comptes portant un role d'administration
 * (ADMIN, ADMIN_SUPPORT ou ADMIN_FINANCE - cf. story 17.4) et disposant d'un mot de passe
 * peuvent se connecter (EPIC-09.1 - "role ADMIN distinct des users").
 * <p>
 * Trois conditions sont exigees, et l'echec de n'importe laquelle produit exactement le meme
 * message : compte inexistant, compte sans role d'administration, ou compte admin sans mot de
 * passe.
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

        if (!user.getRole().isAdmin() || user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new UsernameNotFoundException(GENERIC_FAILURE);
        }

        // L'autorite porte le role REEL du compte, et non ADMIN en dur comme auparavant :
        // c'est elle que lisent les @PreAuthorize, et la figer reviendrait a donner les pleins
        // pouvoirs a tout compte capable de se connecter au back-office.
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }
}
