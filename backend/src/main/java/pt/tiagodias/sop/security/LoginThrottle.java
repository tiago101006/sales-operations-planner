package pt.tiagodias.sop.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Fixed windows are shared by sessions. Active entries are never evicted to admit new keys. */
@Component
public class LoginThrottle {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_KEYS = 10_000;
    private final Clock clock;
    private final Map<String, Window> attempts = new HashMap<>();

    public LoginThrottle() { this(Clock.systemUTC()); }
    LoginThrottle(Clock clock) { this.clock = clock; }

    public synchronized long reserveAddress(String address) { return reserve("ip:" + address, 60); }
    public synchronized long reserveAccount(String email) { return reserve("account:" + email, 5); }
    public synchronized void succeeded(String email) { attempts.remove("account:" + email); }

    private long reserve(String key, int limit) {
        long now = clock.millis();
        attempts.values().removeIf(window -> window.expiresAt <= now);
        Window window = attempts.get(key);
        if (window == null) {
            if (attempts.size() >= MAX_KEYS) { return WINDOW.toSeconds(); }
            window = new Window(now + WINDOW.toMillis());
            attempts.put(key, window);
        }
        if (window.count >= limit) { return Math.max(1, (window.expiresAt - now + 999) / 1000); }
        window.count++;
        return 0;
    }

    private static final class Window {
        final long expiresAt;
        int count;
        Window(long expiresAt) { this.expiresAt = expiresAt; }
    }
}
