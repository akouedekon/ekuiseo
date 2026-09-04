package bj.ekuiseo.api.security;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class EkuiseoUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public EkuiseoUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable : " + phone));
        return EkuiseoUserDetails.from(user);
    }
}
