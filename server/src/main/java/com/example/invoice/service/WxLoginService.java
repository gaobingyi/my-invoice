package com.example.invoice.service;

import com.example.invoice.dto.LoginResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 微信小程序登录：wx.login() 的 code → code2session 换 openid → 白名单校验 → 签发 JWT。
 *
 * 单管理员系统，不做多用户：openid 白名单（WX_ALLOWED_OPENIDS，逗号分隔）命中即视为
 * admin 本人。白名单为空时拒绝所有微信登录——首次绑定时故意留空登录一次，后端日志会
 * 打出尝试者的 openid，填回 .env 重启即可。
 *
 * 安全前提：code2session 对任何打开小程序的微信用户都会成功返回合法 openid，
 * 所以绝不能"首次登录自动建号"，未命中白名单必须 401。
 */
@Service
public class WxLoginService {

    private static final Logger log = LoggerFactory.getLogger(WxLoginService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final JwtTokenService jwtTokenService;
    private final LoginRateLimiter rateLimiter;
    private final String adminUsername;
    private final String appid;
    private final String secret;
    private final Set<String> allowedOpenIds;

    public WxLoginService(RestClient.Builder restClientBuilder,
                          ObjectMapper objectMapper,
                          JwtTokenService jwtTokenService,
                          LoginRateLimiter rateLimiter,
                          @Value("${admin.username:admin}") String adminUsername,
                          @Value("${wx.appid:}") String appid,
                          @Value("${wx.secret:}") String secret,
                          @Value("${wx.allowed-openids:}") String allowedOpenIds) {
        this.restClient = restClientBuilder.baseUrl("https://api.weixin.qq.com").build();
        this.objectMapper = objectMapper;
        this.jwtTokenService = jwtTokenService;
        this.rateLimiter = rateLimiter;
        this.adminUsername = adminUsername;
        this.appid = appid;
        this.secret = secret;
        this.allowedOpenIds = allowedOpenIds == null || allowedOpenIds.isBlank()
                ? Set.of()
                : Arrays.stream(allowedOpenIds.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
    }

    public LoginResponse login(String code, String clientIp) {
        rateLimiter.check(clientIp);
        if (appid.isBlank() || secret.isBlank()) {
            throw new BadCredentialsException("未配置微信登录（WX_APPID / WX_SECRET）");
        }
        String openid = code2Session(code);
        if (!allowedOpenIds.contains(openid)) {
            log.warn("微信登录被拒绝: openid={} 不在白名单内（如需绑定，加入 WX_ALLOWED_OPENIDS 后重启）", openid);
            throw new BadCredentialsException("该微信未绑定");
        }
        return new LoginResponse(jwtTokenService.generate(adminUsername), adminUsername);
    }

    private String code2Session(String code) {
        WxSession session;
        try {
            // 微信部分场景返回 text/plain 的 JSON 体（Content-Type 不规范），RestClient 按
            // Content-Type 选 converter 会找不到 JSON 转换器而抛异常——所以取 String 再手动反序列化。
            String body = restClient.get()
                    .uri(b -> b.path("/sns/jscode2session")
                            .queryParam("appid", appid)
                            .queryParam("secret", secret)
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
            session = objectMapper.readValue(body, WxSession.class);
        } catch (RestClientException | JsonProcessingException e) {
            log.warn("调用微信 code2session 失败: {}", e.getMessage());
            throw new BadCredentialsException("微信登录服务不可用，请稍后再试");
        }
        if (session == null || (session.errcode() != null && session.errcode() != 0)) {
            int errcode = session == null ? -1 : session.errcode() == null ? -1 : session.errcode();
            log.warn("code2session 返回错误: errcode={}, errmsg={}", errcode, session == null ? null : session.errmsg());
            throw new BadCredentialsException("微信登录失败，请重试");
        }
        if (session.openid() == null || session.openid().isBlank()) {
            log.warn("code2session 成功但 openid 为空: {}", session);
            throw new BadCredentialsException("微信登录失败，请重试");
        }
        return session.openid();
    }

    /** 微信 code2session 响应；成功时 errcode 为 null（JSON 缺省），失败时非 0。 */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record WxSession(String openid, String unionid, Integer errcode, String errmsg) {
    }
}
