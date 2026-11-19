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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenProvider tokenProvider;

    @InjectMocks
    private AuthService authService;

    private User sampleUser;
    private Wallet sampleWallet;

    @BeforeEach
    void setUp() {
        sampleUser = new User("alice", "alice@payflow.com", "encodedSecret", UserRole.ROLE_USER);
        sampleUser.setId(UUID.randomUUID());

        sampleWallet = new Wallet(sampleUser, "INR", BigDecimal.valueOf(1000));
        sampleWallet.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Should successfully register a new user and provision an initial wallet")
    void register_Success() {
        RegisterRequest request = new RegisterRequest("alice", "alice@payflow.com", "password123", BigDecimal.valueOf(1000));

        when(userRepository.existsByUsername(request.username())).thenReturn(false);
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("encodedSecret");
        when(userRepository.save(any(User.class))).thenReturn(sampleUser);
        when(walletRepository.save(any(Wallet.class))).thenReturn(sampleWallet);

        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(tokenProvider.generateToken(auth)).thenReturn("mocked-jwt-token");

        AuthResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isEqualTo("mocked-jwt-token");
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.walletId()).isEqualTo(sampleWallet.getId());

        verify(userRepository).save(any(User.class));
        verify(walletRepository).save(any(Wallet.class));
    }

    @Test
    @DisplayName("Should throw UserAlreadyExistsException when username is taken")
    void register_DuplicateUsername_ThrowsException() {
        RegisterRequest request = new RegisterRequest("alice", "alice@payflow.com", "password123", null);
        when(userRepository.existsByUsername(request.username())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessageContaining("Username is already taken");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should successfully authenticate user and return JWT")
    void login_Success() {
        LoginRequest request = new LoginRequest("alice", "password123");
        UserPrincipal principal = UserPrincipal.create(sampleUser);

        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(tokenProvider.generateToken(auth)).thenReturn("login-jwt-token");
        when(walletRepository.findByUserId(sampleUser.getId())).thenReturn(Optional.of(sampleWallet));

        AuthResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("login-jwt-token");
        assertThat(response.userId()).isEqualTo(sampleUser.getId());
        assertThat(response.walletId()).isEqualTo(sampleWallet.getId());
    }
}
