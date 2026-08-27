package com.example.invoice.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InvoiceParser {

    private final InvoiceLlmExtractor llm;
    private final ParsingMetricsService metrics;

    public InvoiceParser(InvoiceLlmExtractor llm, ParsingMetricsService metrics) {
        this.llm = llm;
        this.metrics = metrics;
    }

    // pocfile: PDFBox dumps this PDF so that labels cluster at the top and *values* stream out
    // at the bottom in document order (number, date, buyer-name, buyer-tax, seller-name,
    // seller-tax, subtotals, grand-total, drawer, finally the single detail row). We therefore
    // extract by value pattern + positional order rather than anchoring on a nearby label,
    // which is unreliable when label and value are separated by the column header "项目名称".
    private static final Pattern NAME = Pattern.compile("[\\u4e00-\\u9fa5（）()·]+公司");
    // pocfile: 统一社会信用代码 is 18 chars and may be all digits (914403007084608622) or
    // contain letters (91310116332791646K). The 20-digit 发票号码 differs purely by length,
    // so match exactly 15-18 chars and rely on the length, not a required letter.
    private static final Pattern TAX_ID = Pattern.compile("\\b[0-9A-Z]{15,18}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d{20}");
    private static final Pattern DATE = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    // pocfile: currency mark may be half-width ¥ or full-width ￥, and sits before the
    // number (¥189.62, ￥149.00) or after it (1353.10¥). Match either variant, either side.
    private static final Pattern YEN = Pattern.compile("[¥￥]\\s*([\\d,]+\\.\\d{2})|([\\d,]+\\.\\d{2})\\s*[¥￥]");
    // detail row: "*餐饮服务*餐饮服务 6%189.62 11.38189.621" — first decimal after % is 金额,
    // the second (space-separated) is 税额; the glued trailing 合计 digits are ignored.
    private static final Pattern CATEGORY = Pattern.compile("\\*[^*]+\\*");
    // pocfile: lines of pure CJK that could be the drawer or a company name. We filter corporate
    // keywords in code to pick the drawer.
    // pocfile: PDFBox reorders this layout. Two shapes seen:
    //  - restaurant/furniture: 开票人： label sits in the label cluster, its value streams
    //    out after the grand-total ¥ (¥201.00 王桃桃) — the last pure-CJK token after a ¥.
    //  - Shenzhou: the label and value stay adjacent (开票人：岳云鹏).
    // Try the adjacent label first, then the ¥-adjacent fallback.
    private static final Pattern DRAWER_LABEL = Pattern.compile("开票人[：:]\\s*([\\u4e00-\\u9fa5（）()·]{2,})");
    // pocfile: 与 YEN 一致，金额符号同时接受半角 ¥ 与全角 ￥。
    private static final Pattern DRAWER_YEN = Pattern.compile("[¥￥][\\d,]+\\.\\d{2}\\s*([\\u4e00-\\u9fa5（）()·]{2,})");

    public ParsedInvoice parse(Path pdf) throws IOException {
        ParseContext ctx = (metrics != null) ? new ParseContext() : null;
        String text;
        try {
            text = extractText(pdf);
        } catch (IOException e) {
            if (ctx != null) ctx.setPdfError(true);
            throw e;
        }
        ParsedInvoice parsed = parseText(text);
        if (ctx != null) metrics.recordRegexResult(parsed, ctx);
        // pocfile: regex covers the known layouts; anything it missed is asked of the local
        // LLM as a fallback. null extractor = plain constructor (unit tests).
        if (llm != null) {
            parsed = llm.fill(parsed, text, ctx);
        }
        if (ctx != null) metrics.flush(ctx);
        return parsed;
    }

    public String extractText(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    ParsedInvoice parseText(String text) {
        String number = first(NUMBER, text);
        LocalDate date = null;
        Matcher dm = DATE.matcher(text);
        if (dm.find()) {
            try {
                // pocfile: 非法日期（如 2026年2月30日）会抛 DateTimeException → 500；
                // 置 null 留给 LLM 兜底，与 InvoiceLlmExtractor 对坏数据的处理一致。
                date = LocalDate.of(
                        Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(3)));
            } catch (DateTimeException e) {
                date = null;
            }
        }

        List<String> names = all(NAME, text);          // [buyer, seller] in document order
        List<String> taxIds = all(TAX_ID, text);        // [buyer, seller] in document order
        // pocfile: three ¥ values appear per invoice: 金额合计, 税额合计, 价税合计, and
        // 金额 + 税额 = 价税合计 holds for both sample layouts (189.62+11.38=201.00,
        // 157.52+20.48=178.00). Solve the relation instead of assuming max=价税/min=税额:
        // the max/min heuristics silently mislabel a zero-tax invoice (金额==价税, 税额=0.00)
        // and any invoice where two of the three values collide.
        // pocfile: 三 ¥ 值：金额、税额、价税合计，满足 金额+税额=价税合计（max）且 金额>=税额。
        // 一次扫描取 max / min，再从剩下的挑 mid 验证不变量；O(n) 取代旧 O(n³) 三重循环。
        List<BigDecimal> yens = allDecimal(YEN, text);
        BigDecimal totalAmount = null, taxAmount = null, totalWithTax = null;
        if (yens.size() >= 3) {
            BigDecimal max = yens.get(0), min = max;
            for (BigDecimal y : yens) {
                if (y.compareTo(max) > 0) max = y;
                if (y.compareTo(min) < 0) min = y;
            }
            // pick a value that's neither max nor min — falls back to max when duplicates exist
            BigDecimal mid = max;
            for (BigDecimal y : yens) {
                if (y.compareTo(max) != 0 && y.compareTo(min) != 0) { mid = y; break; }
            }
            // 不变量：金额 + 税额 = 价税合计（max）。不满足时留空，让 LLM 兜底。
            if (mid.add(min).compareTo(max) == 0) {
                totalAmount = mid;
                taxAmount = min;
                totalWithTax = max;
            }
        }

        // pocfile: category is the first *…* token in the detail block. Single-line in the
        // restaurant invoice (*餐饮服务*), wrapped across lines in the furniture one (*家具*).
        String category = first(CATEGORY, text);

        String drawer = null;
        Matcher dl = DRAWER_LABEL.matcher(text);
        if (dl.find()) {
            drawer = dl.group(1);
        } else {
            Matcher dy = DRAWER_YEN.matcher(text);
            while (dy.find()) drawer = dy.group(1);   // keep last ¥-followed CJK token
        }

        String buyerName = names.size() > 0 ? names.get(0) : null;
        String sellerName = names.size() > 1 ? names.get(1) : null;
        // pocfile: the furniture invoice's footer carries a machine code (ALI…8 digits) that
        // also matches the tax-id shape; drop ALI-prefixed tokens before picking the two real
        // 统一社会信用代码 values.
        List<String> realTax = taxIds.stream()
                .filter(t -> !t.startsWith("ALI"))
                .toList();
        String buyerTax = realTax.size() > 0 ? realTax.get(0) : null;
        String sellerTax = realTax.size() > 1 ? realTax.get(1) : null;

        return new ParsedInvoice(number, date, buyerName, buyerTax, sellerName,
                sellerTax, category, totalAmount, taxAmount, totalWithTax, drawer);
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static List<String> all(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static List<BigDecimal> allDecimal(Pattern p, String text) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            out.add(new BigDecimal(v.replace(",", "")));
        }
        return out;
    }
}
