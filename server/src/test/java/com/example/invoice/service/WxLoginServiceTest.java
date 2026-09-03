package com.example.invoice.service;

import com.example.invoice.dto.LoginResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WxLoginServiceTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmn";

    private record TestHarness(WxLoginService service, MockRestServiceServer server) {
    }

    private TestHarness harness(String allowedOpenIds, String appid, String secret) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WxLoginService svc = new WxLoginService(builder, new ObjectMapper(), new JwtTokenService(SECRET, 3600),
                new LoginRateLimiter(false), "admin", appid, secret, allowedOpenIds);
        return new TestHarness(svc, server);
    }

    @Test
    void whitelistedOpenidIssuesAdminJwt() {
        TestHarness h = harness("oAbC123", "test-appid", "test-secret");
        // code2session 的关键参数：js_code=登录 code、appid=小程序 appid
        h.server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("/sns/jscode2session"),
                        org.hamcrest.Matchers.containsString("appid=test-appid"),
                        org.hamcrest.Matchers.containsString("js_code=the-code"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"openid\":\"oAbC123\",\"session_key\":\"k\"}", MediaType.APPLICATION_JSON));

        LoginResponse resp = h.service().login("the-code", "1.2.3.4");

        assertEquals("admin", resp.username());
        assertEquals("admin", new JwtTokenService(SECRET, 3600).parse(resp.token()).getSubject());
        h.server.verify();
    }

    @Test
    void nonWhitelistedOpenidRejected() {
        TestHarness h = harness("oAbC123", "test-appid", "test-secret");
        h.server.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/jscode2session")))
                .andRespond(withSuccess("{\"openid\":\"oEvil\",\"session_key\":\"k\"}", MediaType.APPLICATION_JSON));

        assertThrows(BadCredentialsException.class, () -> h.service().login("the-code", "1.2.3.4"));
        h.server.verify();
    }

    @Test
    void whitelistSupportsMultipleCommaSeparatedOpenids() {
        TestHarness h = harness("oOne, oTwo", "test-appid", "test-secret");
        h.server.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/jscode2session")))
                .andRespond(withSuccess("{\"openid\":\"oTwo\",\"session_key\":\"k\"}", MediaType.APPLICATION_JSON));

        assertEquals("admin", h.service().login("the-code", "1.2.3.4").username());
        h.server.verify();
    }

    @Test
    void wechatErrorCodeMappedToBadCredentials() {
        TestHarness h = harness("oAbC123", "test-appid", "test-secret");
        h.server.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/jscode2session")))
                .andRespond(withSuccess("{\"errcode\":40029,\"errmsg\":\"invalid code\"}", MediaType.APPLICATION_JSON));

        assertThrows(BadCredentialsException.class, () -> h.service().login("the-code", "1.2.3.4"));
        h.server.verify();
    }

    @Test
    void missingWxConfigRejected() {
        // appid/secret 为空时直接拒绝，不发起外部调用
        TestHarness h = harness("oAbC123", "", "");
        assertThrows(BadCredentialsException.class, () -> h.service().login("the-code", "1.2.3.4"));
        h.server.verify();
    }

    @Test
    void emptyOpenidInResponseRejected() {
        TestHarness h = harness("oAbC123", "test-appid", "test-secret");
        h.server.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/jscode2session")))
                .andRespond(withSuccess("{\"session_key\":\"k\"}", MediaType.APPLICATION_JSON));

        assertThrows(BadCredentialsException.class, () -> h.service().login("the-code", "1.2.3.4"));
        h.server.verify();
    }

    @Test
    void textPlainJsonResponseStillParsed() {
        // 微信部分场景返回 Content-Type: text/plain 的 JSON 体，必须能正常解析
        TestHarness h = harness("oAbC123", "test-appid", "test-secret");
        h.server.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/jscode2session")))
                .andRespond(withSuccess("{\"openid\":\"oAbC123\",\"session_key\":\"k\"}", MediaType.TEXT_PLAIN));

        assertEquals("admin", h.service().login("the-code", "1.2.3.4").username());
        h.server.verify();
    }
}
