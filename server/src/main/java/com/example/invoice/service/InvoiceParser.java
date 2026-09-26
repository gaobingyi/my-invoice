package com.example.invoice.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
    // pocfile: 统一社会信用代码 is 18 chars and may be all digits (914403007084608622) or
    // contain letters (91310116332791646K). The 20-digit 发票号码 differs purely by length,
    // so match exactly 15-18 chars and rely on the length, not a required letter.
    // 购/销两列代码被挤成无分隔的一串时（京东票的 36 字符）这里匹配不到 —— 交由 Layout
    // 按字形坐标兜底，见 Layout 注释。
    private static final Pattern TAX_ID = Pattern.compile("\\b[0-9A-Z]{15,18}\\b");
    // pocfile: 号码必须整体 20 位。宽松的 `\d{20}` 会从「35 位数字粘成一串」的信用代码里
    // 截出前 20 位当号码（京东票即如此）。
    private static final Pattern NUMBER = Pattern.compile("\\b\\d{20}\\b");
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
        Layout layout = null;
        try {
            layout = new Layout(pdf);
            text = layout.text;
        } catch (IOException e) {
            if (ctx != null) ctx.setPdfError(true);
            throw e;
        }
        ParsedInvoice parsed = parseText(text);
        // pocfile: 正则文本路径对「标签紧贴值 + 购/销两列同排」的版式无解（见 Layout 注释），
        // 用字形坐标覆盖购/销方名称与代码；取不到就保留正则结果。
        if (layout != null) {
            parsed = layout.overrideParties(parsed);
        }
        if (ctx != null) metrics.recordRegexResult(parsed, ctx);
        // pocfile: regex covers the known layouts; anything it missed is asked of the local
        // LLM as a fallback. null extractor = plain constructor (unit tests).
        if (llm != null) {
            parsed = llm.fill(parsed, text, ctx);
        }
        if (ctx != null) metrics.flush(ctx);
        return parsed;
    }

    /**
     * 文本抽取 + 购/销方字形坐标。
     * <p>pocfile: 值流版式（标签聚顶部、值按文档序流底部）里「名称」和它的信用代码是分开的
     * writeString，两者之间隔着同排的另一列，正则只能靠「代码前最近的中文串」配对。京东票把
     * 购/销两列压成一条物理行：两列名称粘成一个 writeString、两列代码粘成另一个，且没有
     * `\b` 可落脚的分隔符，位置配对与代码切分同时失效（号码还会被 `\d{20}` 从 35 位数字里
     * 截错）。文本顺序无法还原列归属，字形 x 坐标可以。
     */
    private static final class Layout extends PDFTextStripper {
        /** 竖排栏目标签（购/买/方/信/息、销/售）是单字 writeString，会混进名称所在视觉行。 */
        private static final Set<String> LABEL_CHARS = Set.of("购", "买", "销", "售", "方", "信", "息");
        /** 页眉/表头等非名称文本，避免把「开票日期」之类当成公司名。 */
        private static final Pattern NOT_NAME = Pattern.compile(
                "开票日期|发票号码|电子发票|普通发票|名称|识别号|信用代码|项目名称|规格型号|价税合计|开票人|合\\s*计");

        private static final float LINE_TOL = 3f;   // 同一视觉行的 y 容差
        private static final float GAP = 1.6f;      // 字形间距超过它算一个词边界

        private record Glyph(float x, float y, float w, String u) {}
        private record Code(String value, float x, float y, int line) {}

        private final String text;
        private final List<Glyph> glyphs = new ArrayList<>();

        Layout(Path pdf) throws IOException {
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                text = new PDFTextStripper().getText(doc);
                getText(doc);
            }
        }

        @Override
        protected void writeString(String s, List<TextPosition> positions) {
            if (positions.size() == 1 && LABEL_CHARS.contains(positions.get(0).getUnicode())) return;
            for (TextPosition p : positions) {
                glyphs.add(new Glyph(p.getXDirAdj(), p.getYDirAdj(), p.getWidthDirAdj(), p.getUnicode()));
            }
        }

        /** 按 y 分行（行内按 x 排序）；行号供「名称在代码上一行」定位用。 */
        private List<List<Glyph>> lines() {
            List<Glyph> sorted = new ArrayList<>(glyphs);
            sorted.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x));
            List<List<Glyph>> out = new ArrayList<>();
            float currentY = Float.NaN;
            for (Glyph g : sorted) {
                if (out.isEmpty() || Math.abs(g.y() - currentY) > LINE_TOL) {
                    out.add(new ArrayList<>());
                    currentY = g.y();
                }
                out.get(out.size() - 1).add(g);
            }
            return out;
        }

        /** 找形如信用代码的连续 [0-9A-Z] 串；粘连串在此按字形间距切开（京东票的 36 字符串 → 18+18）。 */
        private List<Code> codes(List<List<Glyph>> lines) {
            List<Code> out = new ArrayList<>();
            for (int li = 0; li < lines.size(); li++) {
                List<Glyph> line = lines.get(li);
                for (int i = 0; i < line.size(); i++) {
                    if (!isAlnum(line.get(i))) continue;
                    StringBuilder run = new StringBuilder();
                    float start = line.get(i).x(), end = start;
                    int j = i;
                    while (j < line.size() && isAlnum(line.get(j))
                            && (j == i || line.get(j).x() - end <= GAP)) {
                        run.append(line.get(j).u());
                        end = line.get(j).x() + line.get(j).w();
                        j++;
                    }
                    String value = run.toString();
                    if (value.length() >= 15 && value.length() <= 18) {
                        out.add(new Code(value, start, line.get(i).y(), li));
                    }
                    i = j - 1;
                }
            }
            return out;
        }

        private boolean isAlnum(Glyph g) {
            // getUnicode() 可能是空串（无 ToUnicode 映射的字形），先按长度挡掉再取字符。
            if (g.u().length() != 1) return false;
            char c = g.u().charAt(0);
            return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z');
        }

        /** 同一视觉行内 [lo, hi) 的字形拼成串，间距大处补空格以分隔相邻文本块。 */
        private String slice(List<Glyph> line, float lo, float hi) {
            StringBuilder sb = new StringBuilder();
            float end = Float.NaN;
            for (Glyph g : line) {
                if (g.x() < lo || g.x() >= hi) continue;
                if (!Float.isNaN(end) && g.x() - end > GAP) sb.append(' ');
                sb.append(g.u());
                end = g.x() + g.w();
            }
            return sb.toString();
        }

        /** 代码左侧最近的非标签中文串即该方的名称；逐行上溯（名称可能在代码的上一行）。 */
        private String nameAbove(List<List<Glyph>> lines, int codeLine, float lo, float hi) {
            for (int li = codeLine - 1; li >= 0; li--) {
                String candidate = nameIn(slice(lines.get(li), lo, hi));
                if (candidate != null) return candidate;
            }
            return null;
        }

        private String nameIn(String line) {
            Matcher m = CJK_RUN.matcher(line);
            String last = null;
            while (m.find()) {
                String run = trimLabelTail(m.group());
                if (run.length() >= 4 && !NOT_NAME.matcher(run).find()) last = run;
            }
            return last;
        }

        /** 名称尾部可能粘着标签残字（京东票的「…有限公司名」），去掉再判定。 */
        private String trimLabelTail(String run) {
            int end = run.length();
            while (end > 0) {
                char c = run.charAt(end - 1);
                if (LABEL_CHARS.contains(String.valueOf(c)) || c == '名' || c == '称') end--;
                else break;
            }
            return run.substring(0, end);
        }

        /**
         * 取购/销方的名称与代码：购/销两列的代码在票面上是同一视觉行的一对（且是最上面的一对，
         * 顶部信息区在明细行之前），左侧为购买方、右侧为销售方；名称取各自半区上方最近的中文串。
         * 结构不符合预期时整体放弃，保留正则结果。
         */
        ParsedInvoice overrideParties(ParsedInvoice parsed) {
            List<List<Glyph>> lines = lines();
            List<Code> all = codes(lines);
            if (all.isEmpty()) return parsed;
            float topY = Float.MAX_VALUE;
            for (Code c : all) topY = Math.min(topY, c.y());
            List<Code> pair = new ArrayList<>();
            for (Code c : all) {
                if (c.y() <= topY + LINE_TOL) pair.add(c);
            }
            if (pair.size() != 2) return parsed;
            pair.sort(Comparator.comparingDouble(Code::x));
            Code buyer = pair.get(0), seller = pair.get(1);
            float mid = (buyer.x() + seller.x()) / 2;
            String buyerName = nameAbove(lines, buyer.line(), 0, mid);
            String sellerName = nameAbove(lines, seller.line(), mid, Float.MAX_VALUE);
            return new ParsedInvoice(parsed.invoiceNumber(), parsed.invoiceDate(),
                    buyerName != null ? buyerName : parsed.buyerName(), buyer.value(),
                    sellerName != null ? sellerName : parsed.sellerName(), seller.value(),
                    parsed.category(), parsed.totalAmount(), parsed.taxAmount(), parsed.totalWithTax());
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
