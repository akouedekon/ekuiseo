package bj.ekuiseo.api.security;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.Role;
import bj.ekuiseo.api.domain.enums.UserStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Adapte l'entite {@link User} au contrat Spring Security. */
public record EkuiseoUserDetails(UUID id, String phone, String passwordHash, boolean enabled, Role role) implements UserDetails {

    public static EkuiseoUserDetails from(User user) {
        return new EkuiseoUserDetails(user.getId(), user.getPhone(), user.getPasswordHash(),
                user.getStatus() == UserStatus.ACTIVE, user.getRole());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Tout utilisateur authentifie a ROLE_USER ; ROLE_ADMIN s'ajoute pour le back-office
        // (voir SecurityConfig : /api/v1/admin/** exige hasRole("ADMIN")).
        return role == Role.ADMIN
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return phone;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return enabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
