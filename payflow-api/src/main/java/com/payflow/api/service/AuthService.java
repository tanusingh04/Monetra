package com.payflow.api.service;

import com.payflow.api.dto.auth.AuthResponse;
import com.payflow.api.dto.auth.LoginRequest;
import com.payflow.api.dto.auth.RegisterRequest;
import com.payflow.api.security.JwtTokenProvider;
import com.payflow.api.security.UserPrincipal;
import com.payflow.core.domain.user.User;
import com.payflow.core.domain.user.UserRole;
import com.payflow.core.domain.wallet.Wallet;
import com.payflow.core.exception.UserAlreadyExistsException;
import com.payflow.core.repository.UserRepository;
import com.payflow.core.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;

    public AuthService(UserRepository userRepository,
                       WalletRepository walletRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new UserAlreadyExistsException("Username is already taken: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new UserAlreadyExistsException("Email is already in use: " + request.email());
        }

        User user = new User(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()),
                UserRole.ROLE_USER
        );
        user = userRepository.save(user);

        BigDecimal initialDeposit = request.initialDeposit() != null && request.initialDeposit().compareTo(BigDecimal.ZERO) > 0
                ? request.initialDeposit()
                : BigDecimal.ZERO;

        Wallet wallet = new Wallet(user, "INR", initialDeposit);
        wallet = walletRepository.save(wallet);

        log.info("Registered new user: {} (id: {}) with wallet: {}", user.getUsername(), user.getId(), wallet.getId());

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        String jwt = tokenProvider.generateToken(authentication);

        return new AuthResponse(
                jwt,
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole().name(),
                wallet.getId()
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password())
        );

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        String jwt = tokenProvider.generateToken(authentication);

        UUID walletId = walletRepository.findByUserId(principal.getId())
                .map(Wallet::getId)
                .orElse(null);

        log.info("User logged in successfully: {}", principal.getUsername());

        return new AuthResponse(
                jwt,
                principal.getId(),
                principal.getUsername(),
                principal.getEmail(),
                principal.getAuthorities().iterator().next().getAuthority(),
                walletId
        );
    }
}
