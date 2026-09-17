package org.collegealma.guacamole_seb_validator;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.environment.LocalEnvironment;

final class SebValidation {

    static final String HEADER_NAME             = "X-SafeExamBrowser-ConfigKeyHash";
    static final String SESSION_VALIDATED_AT    = "ca.collegealma.guacamole.seb.validatedAt";
    static final String SESSION_VERSION         = "ca.collegealma.guacamole.seb.version";

    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final long DEFAULT_MAX_AGE_SECONDS = 300L;

    private final Environment environment;
    private final Clock clock;

    SebValidation() {
        this(null, Clock.systemUTC());
    }

    SebValidation(Environment environment, Clock clock) {
        this.environment = environment;
        this.clock = clock;
    }

    private Environment configuration() throws GuacamoleException {
        return environment != null ? environment : LocalEnvironment.getInstance();
    }

    static void clearSessionValidation(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_VALIDATED_AT);
            session.removeAttribute(SESSION_VERSION);
        }
    }

    boolean validateHeader(HttpServletRequest request)
            throws GuacamoleException {

        String received = request.getHeader(HEADER_NAME);
        if (!isHash(received)) {
            return false;
        }

        String absoluteUrl = buildAbsoluteRequestUrl(request);

        return matchesAnyConfigKey(absoluteUrl, received);
    }

    boolean validateJavaScriptValue(HttpServletRequest request, String pageUrl, String received) throws GuacamoleException {

        if (!isHash(received) || pageUrl == null) {
            return false;
        }

        URI page;
        try {
            page = new URI(pageUrl);
        }
        catch (URISyntaxException exception) {
            return false;
        }

        if (page.getFragment() != null || !sameOrigin(request, page) || !withinGuacamoleContext(request, page)) {
            return false;
        }

        return matchesAnyConfigKey(page.toASCIIString(), received);
    }

    void markSessionValidated(HttpServletRequest request, String sebVersion) {
        HttpSession session = request.getSession(true);

        session.setAttribute(SESSION_VALIDATED_AT, clock.instant().getEpochSecond());
        session.setAttribute(SESSION_VERSION, sebVersion == null ? "" : sebVersion);
    }

    boolean isSessionValidated(HttpSession session) throws GuacamoleException {
        if (session == null) {
            return false;
        }

        Object value = session.getAttribute(SESSION_VALIDATED_AT);
        if (!(value instanceof Long)) {
            return false;
        }

        long age = clock.instant().getEpochSecond() - (Long) value;

        return age >= 0 && age <= getMaxAgeSeconds();
    }

    static String buildAbsoluteRequestUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder(request.getRequestURL());
        String query = request.getQueryString();

        if (query != null && !query.isEmpty()) {
            url.append('?').append(query);
        }

        return url.toString();
    }

    static String calculateRequestHash(String absoluteUrl, String configKey) throws GuacamoleServerException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((absoluteUrl + configKey).getBytes(StandardCharsets.UTF_8));

            return toHex(bytes);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new GuacamoleServerException("SHA-256 is unavailable in the Java runtime.", exception);
        }
    }

    private boolean matchesAnyConfigKey(String absoluteUrl,
            String received) throws GuacamoleException {

        byte[] receivedBytes = received.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);

        for (String configKey : getConfigKeys()) {
            String expected = calculateRequestHash(absoluteUrl, configKey);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), receivedBytes)) {
                return true;
            }
        }

        return false;
    }

    private List<String> getConfigKeys() throws GuacamoleException {
        Environment environment = configuration();
        String configured = environment.getRequiredProperty(SebProperties.CONFIG_KEYS);
        List<String> keys = new ArrayList<>();

        for (String value : configured.split(",", -1)) {
            String candidate = value.trim();
            if (!isHash(candidate)) {
                throw new GuacamoleServerException("Every seb-config-keys value must contain exactly 64 hexadecimal characters.");
            }

            keys.add(candidate.toLowerCase(Locale.ROOT));
        }

        if (keys.isEmpty()) {
            throw new GuacamoleServerException("seb-config-keys is empty.");
        }

        return keys;
    }

    long getMaxAgeSeconds() throws GuacamoleException {
        Environment environment = configuration();
        String configured = environment.getProperty(SebProperties.VALIDATION_MAX_AGE_SECONDS);

        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_MAX_AGE_SECONDS;
        }

        try {
            long seconds = Long.parseLong(configured.trim());

            if (seconds < 30 || seconds > 3600) {
                throw new NumberFormatException();
            }

            return seconds;
        }
        catch (NumberFormatException exception) {
            throw new GuacamoleServerException("seb-validation-max-age-seconds must be between 30 and 3600.");
        }
    }

    private static boolean sameOrigin(HttpServletRequest request, URI page) {
        int requestPort = normalizedPort(request.getScheme(), request.getServerPort());
        int pagePort = normalizedPort(page.getScheme(), page.getPort());

        return  request.getScheme().equalsIgnoreCase(page.getScheme()) &&
                request.getServerName().equalsIgnoreCase(page.getHost()) &&
                requestPort == pagePort;
    }

    private static boolean withinGuacamoleContext(HttpServletRequest request, URI page) {
        String context = request.getContextPath();
        String path = page.getPath();

        return path != null && (path.equals(context) || path.startsWith(context + "/"));
    }

    private static int normalizedPort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }

        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static boolean isHash(String value) {
        return value != null && HEX_64.matcher(value).matches();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);

        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }

        return result.toString();
    }
}
