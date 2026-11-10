package com.aquila.auth;

import com.aquila.auth.BootstrapApplicationService.BootstrapMaterial;
import java.io.PrintWriter;
import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public final class LocalAdminCommand implements ApplicationRunner {
    private final BootstrapApplicationService bootstrap;

    public LocalAdminCommand(BootstrapApplicationService bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String command = arguments.containsOption("local-admin-create")
                ? "create"
                : arguments.containsOption("local-admin-reset") ? "reset" : null;
        if (command == null) {
            return;
        }
        java.io.Console console = System.console();
        if (console == null) {
            System.err.println("Local administrator setup requires an interactive console.");
            return;
        }
        execute(
                new String[] {command},
                new Prompter() {
                    @Override
                    public String readUsername() {
                        return console.readLine("Administrator name: ");
                    }

                    @Override
                    public char[] readPassword() {
                        return console.readPassword("Administrator password: ");
                    }
                },
                console.writer());
    }

    public void execute(String[] arguments, Prompter prompter, PrintWriter output) {
        if (arguments == null
                || arguments.length != 1
                || !("create".equals(arguments[0]) || "reset".equals(arguments[0]))) {
            output.println("Usage: aquila-server --local-admin-create | --local-admin-reset");
            output.flush();
            return;
        }
        char[] password = null;
        try {
            String username = prompter.readUsername();
            password = prompter.readPassword();
            BootstrapMaterial material = "create".equals(arguments[0])
                    ? bootstrap.createAdministrator(username, password)
                    : bootstrap.resetAdministrator(username, password);
            output.println("Local administrator bootstrap prepared.");
            output.println("One-time bootstrap token (expires in 15 minutes): " + material.bootstrapToken());
            output.println("TOTP secret (Base32, shown once): " + material.totpSecret());
            output.println("Authenticator URI (shown once): " + material.otpauthUri());
        } catch (Exception exception) {
            output.println("Unable to initialize the administrator. Check the isolated database and try again.");
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            output.flush();
        }
    }

    public interface Prompter {
        String readUsername();

        char[] readPassword();
    }
}
