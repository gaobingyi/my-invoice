// Package money 提供精确十进制金额类型：内部以 plain notation 字符串保存
//（不进浮点），对齐 Java 侧「SQLite TEXT 列存 BigDecimal.toPlainString()」的约定。
package money

import (
	"fmt"
	"math/big"
	"regexp"
	"strings"
)

// Decimal 零值表示 NULL（与 Java BigDecimal null 对应）；否则保存
// `[-]?\d+(\.\d+)?` 字面量（已去千分位逗号与多余前导零，保留小数位 scale，
// 如 "201.00"、"0.00"）。
type Decimal struct{ s string }

var reDecimal = regexp.MustCompile(`^[+-]?\d+(\.\d+)?$`)

// New 解析字面量为 Decimal；非法输入返回 error（内部路径用，LLM merge 路径用 Null 友好版）。
// 行为对齐 new BigDecimal(v.replace(",", ""))：接受逗号分隔，保留原 scale。
func New(literal string) (Decimal, error) {
	s := strings.ReplaceAll(literal, ",", "")
	if s == "" {
		return Decimal{}, fmt.Errorf("empty decimal")
	}
	if !reDecimal.MatchString(s) {
		return Decimal{}, fmt.Errorf("invalid decimal: %q", literal)
	}
	neg := false
	switch s[0] {
	case '+':
		s = s[1:]
	case '-':
		neg = true
		s = s[1:]
	}
	intPart, frac := splitScale(s)
	intPart = strings.TrimLeft(intPart, "0")
	if intPart == "" {
		intPart = "0"
	}
	out := intPart
	if frac != "" {
		out += "." + frac
	}
	if neg && !isZero(out) {
		out = "-" + out
	}
	return Decimal{s: out}, nil
}

// Must 同 New，内部常量场景（如 "0.00"）用；非法输入 panic。
func Must(literal string) Decimal {
	d, err := New(literal)
	if err != nil {
		panic(err)
	}
	return d
}

// FromInt 构造整数金额（scale 0）。测试用。
func FromInt(n int64) Decimal { return Decimal{s: fmt.Sprintf("%d", n)} }

func (d Decimal) IsNull() bool { return d.s == "" }

// MarshalJSON 输出 JSON number 字面量（保留 scale，如 201.00），NULL → null。
// 对齐 Jackson 对 BigDecimal 的序列化。
func (d Decimal) MarshalJSON() ([]byte, error) {
	if d.IsNull() {
		return []byte("null"), nil
	}
	if !reDecimal.MatchString(d.s) {
		return nil, fmt.Errorf("invalid decimal literal: %q", d.s)
	}
	return []byte(d.s), nil
}

// String 返回 plain notation；NULL 返回空串。
func (d Decimal) String() string { return d.s }

// IsZero 判断数值是否为 0（NULL 恒 false）。
func (d Decimal) IsZero() bool { return !d.IsNull() && isZero(d.s) }

// Compare 数值比较（scale 无关）。NULL 视为最小（调用方保证不会传 NULL）。
func Compare(a, b Decimal) int {
	if a.IsNull() && b.IsNull() {
		return 0
	}
	if a.IsNull() {
		return -1
	}
	if b.IsNull() {
		return 1
	}
	ai, as := toScaledInt(a.s, 0)
	bi, bs := toScaledInt(b.s, 0)
	scale := max(as, bs)
	ai = rescale(ai, as, scale)
	bi = rescale(bi, bs, scale)
	return ai.Cmp(bi)
}

// Add 精确加法（结果 scale = 两操作数较大者，对齐 BigDecimal.add 的 preferred scale）。
func Add(a, b Decimal) Decimal {
	if a.IsNull() || b.IsNull() {
		return Decimal{} // 调用方语义：null 参与不求和
	}
	ai, as := toScaledInt(a.s, 0)
	bi, bs := toScaledInt(b.s, 0)
	scale := max(as, bs)
	ai = rescale(ai, as, scale)
	bi = rescale(bi, bs, scale)
	return fromScaledInt(new(big.Int).Add(ai, bi), scale)
}

// ────── 内部工具 ──────

func splitScale(s string) (intPart, frac string) {
	if i := strings.IndexByte(s, '.'); i >= 0 {
		return s[:i], s[i+1:]
	}
	return s, ""
}

// toScaledInt 把字面量转成 (去小数点的整数, scale)。
func toScaledInt(s string, _ int) (*big.Int, int) {
	intPart, frac := splitScale(s)
	neg := strings.HasPrefix(intPart, "-")
	digits := strings.ReplaceAll(intPart, "-", "") + frac
	v := new(big.Int)
	v.SetString(digits, 10)
	if neg {
		v.Neg(v)
	}
	return v, len(frac)
}

func rescale(v *big.Int, from, to int) *big.Int {
	for from < to {
		v = new(big.Int).Mul(v, big.NewInt(10))
		from++
	}
	return v
}

func fromScaledInt(v *big.Int, scale int) Decimal {
	s := v.String()
	neg := strings.HasPrefix(s, "-")
	digits := strings.TrimPrefix(s, "-")
	var out string
	if scale == 0 {
		out = digits
	} else {
		for len(digits) <= scale {
			digits = "0" + digits
		}
		out = digits[:len(digits)-scale] + "." + digits[len(digits)-scale:]
	}
	if neg && !isZero(out) {
		out = "-" + out
	}
	return Decimal{s: out}
}

func isZero(plain string) bool {
	for _, c := range plain {
		if c != '0' && c != '.' && c != '-' {
			return false
		}
	}
	return true
}
