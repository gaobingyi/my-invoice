package com.example.invoice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceServiceTest {

    private static final String REQUIRED_NAME = "上海钦钦印刷科技有限公司";
    private static final String REQUIRED_TAX_ID = "91310116332791646K";

    private static ParsedInvoice parsed(String buyerName, String buyerTaxId) {
        // 其余字段与购买方校验无关，一律 null。
        return new ParsedInvoice(null, null, buyerName, buyerTaxId,
                null, null, null, null, null, null);
    }

    @Test
    void matchingBuyerAccepted() {
        assertDoesNotThrow(() -> InvoiceService.validateBuyer(parsed(REQUIRED_NAME, REQUIRED_TAX_ID)));
    }

    @Test
    void buyerNameWithSurroundingWhitespaceAccepted() {
        // LLM 兜底可能带首位空格，trim 归一化后应通过。
        assertDoesNotThrow(() ->
                InvoiceService.validateBuyer(parsed("  " + REQUIRED_NAME + " ", REQUIRED_TAX_ID)));
    }

    @Test
    void wrongBuyerNameRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InvoiceService.validateBuyer(parsed("测试买家公司", REQUIRED_TAX_ID)));
        assertTrue(e.getMessage().contains("测试买家公司"));
        assertTrue(e.getMessage().contains(REQUIRED_NAME));
    }

    @ParameterizedTest
    @MethodSource("rejectedBuyers")
    void nonMatchingBuyerRejected(String buyerName, String buyerTaxId) {
        assertThrows(IllegalArgumentException.class,
                () -> InvoiceService.validateBuyer(parsed(buyerName, buyerTaxId)));
    }

    private static Stream<Arguments> rejectedBuyers() {
        return Stream.of(
                Arguments.of(REQUIRED_NAME, "99999999999999999X"), // 名称匹配、代码不符
                Arguments.of(REQUIRED_NAME, null),                 // 代码解析不出
                Arguments.of(null, null)                           // 名称与代码都解析不出
        );
    }

    @Test
    void nullBuyerNameRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InvoiceService.validateBuyer(parsed(null, REQUIRED_TAX_ID)));
        assertTrue(e.getMessage().contains("[]"));
    }

    @Test
    void wrongNameAndTaxIdBothReportedInMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InvoiceService.validateBuyer(parsed("甲公司", "12345678901234567X")));
        assertTrue(e.getMessage().contains("甲公司"));
        assertTrue(e.getMessage().contains("12345678901234567X"));
        assertEquals("购买方校验失败：解析到购买方 [甲公司]，信用代码 [12345678901234567X]；"
                        + "本系统仅接受购买方为「" + REQUIRED_NAME + "」（信用代码 "
                        + REQUIRED_TAX_ID + "）的发票",
                e.getMessage());
    }
}