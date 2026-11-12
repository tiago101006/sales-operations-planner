package pt.tiagodias.sop.identity;

import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("bootstrap-admin")
@ConditionalOnNotWebApplication
public class BootstrapAdmin implements ApplicationRunner {
    private final IdentityService service;
    private final Environment environment;
    private final Validator validator;
    private final ConfigurableApplicationContext context;

    public BootstrapAdmin(IdentityService service, Environment environment, Validator validator,
                          ConfigurableApplicationContext context) {
        this.service = service;
        this.environment = environment;
        this.validator = validator;
        this.context = context;
    }

    private record BootstrapRequest(String nome, @NotBlank @Email @Size(max=254) String email,
            @NotBlank @Size(min=12,max=72) String password) {}

    @Override
    public void run(ApplicationArguments args) {
        var request = new BootstrapRequest("Administrador", environment.getRequiredProperty("SOP_ADMIN_EMAIL"),
                environment.getRequiredProperty("SOP_ADMIN_PASSWORD"));
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalArgumentException("Email inválido ou password fora dos limites (12 caracteres a 72 bytes UTF-8).");
        }
        service.createFirstAdmin(request.nome(), request.email(), request.password());
        System.out.println("Primeira conta ADMIN criada. Nenhuma password foi apresentada ou guardada em texto simples.");
        context.close();
    }
}
