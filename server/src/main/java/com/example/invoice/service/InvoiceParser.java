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
    // seller-tax, subtotals, grand-total, finally the single detail row). We therefore
    // extract by value pattern + positional order rather than anchoring on a nearby label,
    // which is unreliable when label and value are separated by the column header "项目名称".
    private static final Pattern NAME = Pattern.compile("[\\u4e00-\\u9fa5（）()·]+公司");
    // pocfile: 名称不限定「公司」结尾——个体户名称（…商店/中心/厂）匹配不上 NAME，
    // 会轮到备注里「销方开户银行:…公司」顶替真实销售方。值流里名称紧贴其信用代码之前
    // （购方名称→购方代码→销方名称→销方代码），故取每个代码前最近的连续中文串配对。
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5（）()·]{4,}");
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
    // 金额符号同时接受半角 ¥ 与全角 ￥。

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

        List<String> names = all(NAME, text);          // fallback: [buyer, seller] in document order
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

        // pocfile: 名称与信用代码按位置配对（见 CJK_RUN 注释）；配不上时退回旧 NAME 列表逻辑。
        List<TaxIdAt> taxes = realTaxIds(text);
        String buyerName = taxes.size() > 0 ? nameBefore(text, taxes.get(0).start()) : null;
        String sellerName = taxes.size() > 1 ? nameBefore(text, taxes.get(1).start()) : null;
        if (buyerName == null) buyerName = names.size() > 0 ? names.get(0) : null;
        if (sellerName == null) sellerName = names.size() > 1 ? names.get(1) : null;
        String buyerTax = taxes.size() > 0 ? taxes.get(0).value() : null;
        String sellerTax = taxes.size() > 1 ? taxes.get(1).value() : null;

        return new ParsedInvoice(number, date, buyerName, buyerTax, sellerName,
                sellerTax, category, totalAmount, taxAmount, totalWithTax);
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    private record TaxIdAt(String value, int start) {}

    // pocfile: 信用代码带位置收集；家具发票页脚的机器码（ALI…）也匹配代码形状，仍按前缀剔除。
    private static List<TaxIdAt> realTaxIds(String text) {
        List<TaxIdAt> out = new ArrayList<>();
        Matcher m = TAX_ID.matcher(text);
        while (m.find()) {
            if (m.group().startsWith("ALI")) continue;
            out.add(new TaxIdAt(m.group(), m.start()));
        }
        return out;
    }

    private static String nameBefore(String text, int pos) {
        Matcher m = CJK_RUN.matcher(text);
        m.region(0, pos);
        String last = null;
        while (m.find()) {
            String g = m.group();
            // 标签与名称粘连的版式（...上海钦钦印刷科技有限公司统一社会信用代码:913...）
            // CJK_RUN 会把"公司名+标签"合并为一个 token，整串被误判为标签而丢弃。
            // 若 token 内含标签关键字，取标签前的 prefix 作为候选名称。
            int cut = -1;
            int i1 = g.indexOf("统一社会信用代码");
            int i2 = g.indexOf("纳税人识别号");
            if (i1 >= 0) cut = i1;
            else if (i2 >= 0) cut = i2;
            if (cut >= 0) {
                String prefix = g.substring(0, cut);
                if (prefix.length() >= 4 && !isLabelRun(prefix)) last = prefix;
                continue;
            }
            if (isLabelRun(g)) continue;
            last = g;
        }
        return last;
    }

    private static boolean isLabelRun(String run) {
        // 精确匹配完整标签短语，避免 endsWith("信用代码") 误伤
        // 如"上海信用代码服务中心"这类合法公司名。
        return run.contains("纳税人识别号") || run.contains("统一社会信用代码");
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
