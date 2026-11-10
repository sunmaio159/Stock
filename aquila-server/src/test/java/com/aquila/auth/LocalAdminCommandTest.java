package com.aquila.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aquila.auth.BootstrapApplicationService.BootstrapMaterial;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class LocalAdminCommandTest {
    @Test
    void commandDoesNotEchoSecretsFromArgumentsOrBootstrapFailures() {
        BootstrapApplicationService bootstrap = mock(BootstrapApplicationService.class);
        String password = "never-print-this-password";
        String token = "never-print-this-token";
        when(bootstrap.createAdministrator(eq("local-admin"), any(char[].class)))
                .thenThrow(new IllegalStateException(password + " " + token));
        LocalAdminCommand command = new LocalAdminCommand(bootstrap);
        StringWriter captured = new StringWriter();
        char[] suppliedPassword = password.toCharArray();

        command.execute(
                new String[] {"create"},
                new LocalAdminCommand.Prompter() {
                    @Override
                    public String readUsername() {
                        return "local-admin";
                    }

                    @Override
                    public char[] readPassword() {
                        return suppliedPassword;
                    }
                },
                new PrintWriter(captured, true));

        assertThat(captured.toString()).doesNotContain(password, token);
        ArgumentCaptor<char[]> passwordCaptor = ArgumentCaptor.forClass(char[].class);
        verify(bootstrap).createAdministrator(eq("local-admin"), passwordCaptor.capture());
        assertThat(passwordCaptor.getValue()).isSameAs(suppliedPassword);
        assertThat(suppliedPassword).containsOnly('\0');

        StringWriter rejectedOutput = new StringWriter();
        LocalAdminCommand.Prompter rejectedPrompter = mock(LocalAdminCommand.Prompter.class);
        command.execute(
                new String[] {"create", password},
                rejectedPrompter,
                new PrintWriter(rejectedOutput, true));
        assertThat(rejectedOutput.toString()).doesNotContain(password, token);
        verifyNoInteractions(rejectedPrompter);
    }

    @Test
    void successfulBootstrapMaterialIsDisplayedOnlyByTheLocalConsole() {
        BootstrapApplicationService bootstrap = mock(BootstrapApplicationService.class);
        when(bootstrap.createAdministrator(eq("local-admin"), any(char[].class)))
                .thenReturn(new BootstrapMaterial("one-time-token", "BASE32SECRET", "otpauth://local"));
        LocalAdminCommand command = new LocalAdminCommand(bootstrap);
        StringWriter captured = new StringWriter();
        char[] suppliedPassword = "actual-password-value".toCharArray();

        command.execute(
                new String[] {"create"},
                new LocalAdminCommand.Prompter() {
                    @Override
                    public String readUsername() {
                        return "local-admin";
                    }

                    @Override
                    public char[] readPassword() {
                        return suppliedPassword;
                    }
                },
                new PrintWriter(captured, true));

        assertThat(captured.toString()).contains("one-time-token", "BASE32SECRET");
        assertThat(captured.toString()).doesNotContain("actual-password-value");
        assertThat(suppliedPassword).containsOnly('\0');
    }

    @Test
    void localResetDoesNotEchoTheNewPasswordOrBootstrapMaterialOnFailure() {
        BootstrapApplicationService bootstrap = mock(BootstrapApplicationService.class);
        char[] suppliedPassword = "new-local-secret".toCharArray();
        when(bootstrap.resetAdministrator(eq("local-admin"), any(char[].class)))
                .thenThrow(new IllegalStateException("new-local-secret one-time-token"));
        LocalAdminCommand command = new LocalAdminCommand(bootstrap);
        StringWriter captured = new StringWriter();

        command.execute(
                new String[] {"reset"},
                new LocalAdminCommand.Prompter() {
                    @Override
                    public String readUsername() {
                        return "local-admin";
                    }

                    @Override
                    public char[] readPassword() {
                        return suppliedPassword;
                    }
                },
                new PrintWriter(captured, true));

        assertThat(captured.toString()).doesNotContain("new-local-secret", "one-time-token");
        assertThat(suppliedPassword).containsOnly('\0');
    }
}
