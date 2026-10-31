// Package auth：JWT 签发/校验、登录限流、单管理员 seed 与微信登录。
package auth

import (
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// TokenService 签发/校验 HS256 JWT（sub=username，iat/exp）。
// 有意偏离：Java 版 jjwt 按密钥长度自动选 HS256/384/512，这里固定 HS256——
// 两套实例不共享 token，无兼容需求，固定算法更可预期。
type TokenService struct {
	secret []byte
	expire time.Duration
}

func NewTokenService(secret string, expireSeconds int) *TokenService {
	return &TokenService{secret: []byte(secret), expire: time.Duration(expireSeconds) * time.Second}
}

func (s *TokenService) Issue(username string) (string, error) {
	now := time.Now()
	claims := jwt.RegisteredClaims{
		Subject:   username,
		IssuedAt:  jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(now.Add(s.expire)),
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(s.secret)
}

// Parse 校验并返回 sub（username）。过期/篡改/算法不符返回 error → 上层 401。
func (s *TokenService) Parse(tokenStr string) (string, error) {
	tok, err := jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return s.secret, nil
	}, jwt.WithValidMethods([]string{"HS256"}))
	if err != nil {
		return "", err
	}
	claims, ok := tok.Claims.(jwt.MapClaims)
	if !ok || !tok.Valid {
		return "", errors.New("invalid token")
	}
	sub, _ := claims["sub"].(string)
	if sub == "" {
		return "", errors.New("empty subject")
	}
	return sub, nil
}
