package pt.tiagodias.sop.security;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;

final class SecurityErrors {
    private SecurityErrors() {}

    static void write(HttpServletResponse response, int status, String code, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"urn:sop:erro:" + code + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\",\"title\":\"" + title + "\"}");
    }
}
