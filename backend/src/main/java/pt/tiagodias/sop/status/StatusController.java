package pt.tiagodias.sop.status;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    private final String applicationName;

    public StatusController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping
    public StatusResponse status() {
        return new StatusResponse(applicationName);
    }

    public record StatusResponse(String application) {}
}
