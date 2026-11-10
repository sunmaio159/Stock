package com.aquila.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import org.junit.jupiter.api.Test;

class SameOriginPackagingTest {
    @Test
    void frontendEntryPointIsOnTheServerClasspath() {
        URL entryPoint = getClass().getClassLoader().getResource("static/index.html");

        assertThat(entryPoint).as("packaged same-origin frontend entry point").isNotNull();
    }
}
