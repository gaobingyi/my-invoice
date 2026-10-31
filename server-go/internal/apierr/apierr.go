// Package apierr 定义业务错误类型，HTTP 层据此映射状态码。
// 错误响应体是 text/plain 字符串（对齐 Java GlobalExceptionHandler）。
package apierr

import "fmt"

// BadRequest 业务校验失败 → 400（对齐 IllegalArgumentException）。
type BadRequest struct{ Msg string }

func (e *BadRequest) Error() string { return e.Msg }

func Bad(format string, args ...any) *BadRequest {
	return &BadRequest{Msg: fmt.Sprintf(format, args...)}
}

// Duplicate 唯一键冲突 → 409（发票号码 / 批次月份 / 批次条目）。
type Duplicate struct{ Msg string }

func (e *Duplicate) Error() string { return e.Msg }

// TooLarge 上传超限 → 413。
type TooLarge struct{ Msg string }

func (e *TooLarge) Error() string { return e.Msg }

// Internal 服务器错误 → 500（响应体固定文案，不回传内部信息）。
type Internal struct {
	Err error
	Msg string
}

func (e *Internal) Error() string { return e.Msg }

func (e *Internal) Unwrap() error { return e.Err }
