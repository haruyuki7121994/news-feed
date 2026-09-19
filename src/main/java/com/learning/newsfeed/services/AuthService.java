package com.learning.newsfeed.services;

import com.learning.newsfeed.controllers.AuthController;
import com.learning.newsfeed.entities.Authentication;
import com.learning.newsfeed.repositories.AuthenticationRepository;
import com.learning.newsfeed.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationRepository authenticationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;

    public AuthController.TokenResponse login(AuthController.LoginRequest request) {
        var authentication = authenticationRepository.query(Authentication.USER_PREFIX + request.username())
                .orElseThrow(this::invalidCredentials);
        log.info("authentication: {}", authentication);
        var user = userRepository.query(authentication.getUserId()).orElseThrow();

        if (!passwordEncoder.matches(
                request.password(),
                user.getPasswordHash())) {
            throw invalidCredentials();
        }

        Set<String> scopes = authentication.getScopes();
        String accessToken = accessTokenService.issue(user.getUserId(), scopes);
        String refreshToken = refreshTokenService.issue(user.getUserId());

        return new AuthController.TokenResponse(accessToken, refreshToken, "Bearer", 4 * 60 * 60);
    }

    public AuthController.TokenResponse refresh(String rawRefreshToken) {
        // Kiểm tra và đổi token cũ thành token mới một cách atomic.
        var rotated = refreshTokenService.rotate(rawRefreshToken);
        var user = userRepository.query(rotated.userId()).orElseThrow();
        var authentication = authenticationRepository.query(Authentication.USER_PREFIX + user.getUsername())
                .orElseThrow(this::invalidCredentials);

        return new AuthController.TokenResponse(
                accessTokenService.issue(rotated.userId(), authentication.getScopes()),
                rotated.refreshToken(),
                "Bearer",
                900
        );
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid username or password"
        );
    }
}
