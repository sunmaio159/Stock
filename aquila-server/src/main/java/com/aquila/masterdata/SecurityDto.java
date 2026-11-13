package com.aquila.masterdata;

public record SecurityDto(
        String securityCode,
        String exchange,
        String code,
        String securityName,
        String securityType,
        String status) {}
