package com.example.invoice.service;

import com.example.invoice.dto.InvoiceSummaryResponse;
import com.example.invoice.entity.Invoice;
import com.example.invoice.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
        assertTrue(e.getMessage().contains("信用代码"));
    }

    @Test
    void wrongNameAndTaxIdBothReportedInMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InvoiceService.validateBuyer(parsed("甲公司", "12345678901234567X")));
        assertEquals("购买方校验失败：甲公司（12345678901234567X）", e.getMessage());
    }

    // ---- summary：与列表同一过滤条件下的张数 + 价税合计（仓库层 JDK 代理 fake，
    // 与 ExportServiceTest 同款：Mockito 的 Byte Buddy 在 Java 25 不可用）----

    @SuppressWarnings("unchecked")
    private static InvoiceService serviceWith(List<Invoice> all) {
        // 无参 findAll（无过滤）与 findAll(spec)（used/keyword 过滤）都返回同一份 fake
        InvoiceRepository repo = proxy(InvoiceRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findAll" -> all;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        return new InvoiceService(repo, null, null, "./uploads");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, java.lang.reflect.InvocationHandler h) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
    }

    private static Invoice invoice(String totalWithTax) {
        Invoice inv = new Invoice();
        inv.setTotalWithTax(totalWithTax == null ? null : new BigDecimal(totalWithTax));
        return inv;
    }

    @Test
    void summarySumsNonNullOrTotals() {
        InvoiceService service = serviceWith(new ArrayList<>(List.of(
                invoice("559.23"), invoice("100.00"), invoice(null))));
        InvoiceSummaryResponse s = service.summary(null, null);
        assertEquals(3, s.count());
        assertEquals(0, s.totalWithTax().compareTo(new BigDecimal("659.23")));
    }

    @Test
    void summaryEmptySetIsZero() {
        InvoiceSummaryResponse s = serviceWith(new ArrayList<>()).summary(null, null);
        assertEquals(0, s.count());
        assertEquals(0, s.totalWithTax().compareTo(BigDecimal.ZERO));
    }

    @Test
    void summaryWithFiltersDelegatesToSpecFindAll() {
        // used/keyword 走 Specification 路径（filterSpec 返回非 null），不因构造 spec 抛错
        InvoiceSummaryResponse s = serviceWith(new ArrayList<>(List.of(invoice("1.00"))))
                .summary(false, "京东");
        assertEquals(1, s.count());
    }
}