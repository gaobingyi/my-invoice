package com.example.invoice.dto;

import jakarta.validation.constraints.NotBlank;

public record WxLoginRequest(
        @NotBlank String code) {
}
