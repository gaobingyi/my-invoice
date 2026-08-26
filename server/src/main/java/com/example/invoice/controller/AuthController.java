package com.example.invoice.controller;

import com.example.invoice.dto.LoginRequest;
import com.example.invoice.dto.LoginResponse;
import com.example.invoice.service.AuthService;
import com.example.invoice.service.BadCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final boolean trustProxy;

    public AuthController(AuthService authService,
                          @Value("${trust-proxy:false}") boolean trustProxy) {
        this.authService = authService;
        this.trustProxy = trustProxy;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        return authService.login(req, clientIp(request));
    }

    /** 公开探活端点，供 Docker healthcheck（业务端点已 401 保护）。 */
    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<String> badCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }

    private String clientIp(HttpServletRequest req) {
        // pocfile: 只有部署在可信反向代理之后（compose 里 nginx 设 trust-proxy=true）
        // 才信任 X-Real-IP——nginx 会覆盖客户端带来的该头。dev（vite proxy）或直连 8080 时
        // X-Real-IP 可被客户端伪造，信任它会绕过登录限流（轮换身份），所以默认不信任。
        // 不用 X-Forwarded-For：它会追加客户端自带的 XFF 头，split(",")[0] 取到的是
        // 可伪造值，攻击者可借此绕过登录限流并定向陷害某 IP。
        if (trustProxy) {
            String realIp = req.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) return realIp.trim();
        }
        return req.getRemoteAddr();
    }
}
