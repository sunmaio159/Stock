package com.aquila.masterdata;

public record ImportResult(String batchId, int committedRows, String status) {}
