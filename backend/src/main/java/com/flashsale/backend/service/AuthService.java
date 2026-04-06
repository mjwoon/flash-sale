package com.flashsale.backend.service;

import com.flashsale.backend.domain.User;
import com.flashsale.backend.dto.request.LoginRequest;
import com.flashsale.backend.dto.request.RefreshTokenRequest;
import com.flashsale.backend.dto.request.RegisterRequest;
import com.flashsale.backend.dto.response.JwtResponse;
import com.flashsale.backend.exception.InvalidRefreshTokenException;
import com.flashsale.backend.exception.UserAlreadyExistsException;
import com.flashsale.backend.repository.UserRepository;
import com.flashsale.backend.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public JwtResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException("email", request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .phone(request.getPhone())
                .role(User.Role.USER)
                .build();

        String refreshToken = jwtTokenProvider.generateRefreshToken();
        user.setRefreshToken(refreshToken);

        User saved = userRepository.save(user);
        log.info("New user registered: {}", saved.getEmail());

        String accessToken = jwtTokenProvider.generateToken(saved.getEmail(), saved.getRole().name());
        return JwtResponse.of(accessToken, refreshToken, jwtTokenProvider.getExpiresInSeconds(),
                saved.getId(), saved.getEmail(), saved.getName(), saved.getRole().name());
    }

    @Transactional
    public JwtResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .map(a -> a.replace("ROLE_", ""))
                .orElse("USER");

        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("User not found after authentication: " + email));

        String refreshToken = jwtTokenProvider.generateRefreshToken();
        user.setRefreshToken(refreshToken);
        userRepository.save(user);

        log.info("User logged in: {}", email);
        String accessToken = jwtTokenProvider.generateToken(email, role);
        return JwtResponse.of(accessToken, refreshToken, jwtTokenProvider.getExpiresInSeconds(),
                user.getId(), user.getEmail(), user.getName(), role);
    }

    @Transactional
    public JwtResponse refreshToken(RefreshTokenRequest request) {
        User user = userRepository.findByRefreshToken(request.getRefreshToken())
                .orElseThrow(InvalidRefreshTokenException::new);

        String newAccessToken = jwtTokenProvider.generateToken(user.getEmail(), user.getRole().name());
        log.info("Access token refreshed for user: {}", user.getEmail());

        return JwtResponse.of(newAccessToken, user.getRefreshToken(), jwtTokenProvider.getExpiresInSeconds(),
                user.getId(), user.getEmail(), user.getName(), user.getRole().name());
    }

    @Transactional(readOnly = true)
    public User getCurrentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("User not found: " + email));
    }
}
