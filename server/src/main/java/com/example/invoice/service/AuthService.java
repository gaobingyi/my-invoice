package com.example.invoice.service;

import com.example.invoice.dto.LoginRequest;
import com.example.invoice.dto.LoginResponse;
import com.example.invoice.entity.User;
import com.example.invoice.repository.UserRepository;
import com.example.invoice.service.BadCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final LoginRateLimiter rateLimiter;
    private final String adminUsername;
    private final String adminPassword;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       LoginRateLimiter rateLimiter,
                       @Value("${admin.username:admin}") String adminUsername,
                       @Value("${admin.password:admin123}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.rateLimiter = rateLimiter;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    /** 启动时若无用户则 seed 管理员（BCrypt 散列入库，不存明文）。 */
    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }
        User user = new User();
        user.setUsername(adminUsername);
        user.setPasswordHash(passwordEncoder.encode(adminPassword));
        userRepository.save(user);
        log.info("已初始化管理员账号: {}", adminUsername);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req, String clientIp) {
        rateLimiter.check(clientIp);
        User user = userRepository.findByUsername(req.username())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        return new LoginResponse(jwtTokenService.generate(user.getUsername()), user.getUsername());
    }
}
