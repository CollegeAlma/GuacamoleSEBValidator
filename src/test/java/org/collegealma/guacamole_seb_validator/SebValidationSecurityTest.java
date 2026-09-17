package org.collegealma.guacamole_seb_validator;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.Response;
import org.apache.guacamole.GuacamoleSecurityException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.net.auth.AbstractAuthenticatedUser;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.apache.guacamole.properties.GuacamoleProperty;
import org.junit.jupiter.api.Test;

class SebValidationSecurityTest {
    private static final String KEY = "abcdef0123456789".repeat(4);
    private static final String PAGE = "https://exam.test/guacamole/";
    private static final long NOW = 10000;
    private final Map<String, String> properties = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private boolean hasSession;
    private int createdSessions;
    private String header;
    private String query;
    private int port = 443;
    private String scheme = "https";
    private final HttpSession session = proxy(HttpSession.class, (method, args) -> {
        switch (method) {
            case "getAttribute": return attributes.get(args[0]);
            case "setAttribute": attributes.put((String) args[0], args[1]); return null;
            case "removeAttribute": attributes.remove(args[0]); return null;
            default: throw new AssertionError("Unexpected session operation: " + method);
        }
    });
    private final HttpServletRequest request = proxy(HttpServletRequest.class, (method, args) -> {
        switch (method) {
            case "getSession":
                if (!hasSession && (args == null || Boolean.TRUE.equals(args[0]))) {
                    hasSession = true; createdSessions++;
                }
                return hasSession ? session : null;
            case "getHeader": return SebValidation.HEADER_NAME.equals(args[0]) ? header : null;
            case "getRequestURL": return new StringBuffer(PAGE + "api/tokens");
            case "getQueryString": return query;
            case "getScheme": return scheme;
            case "getServerName": return "exam.test";
            case "getServerPort": return port;
            case "getContextPath": return "/guacamole";
            case "getRemoteAddr": return "127.0.0.1";
            case "getRemoteHost": return "localhost";
            case "getCookies": case "getHeaderNames": case "getParameterNames": return null;
            default: throw new AssertionError("Unexpected request operation: " + method);
        }
    });
    private final Environment environment = proxy(Environment.class, (method, args) -> {
        if (method.equals("getProperty") || method.equals("getRequiredProperty")) {
            String value = properties.get(((GuacamoleProperty<?>) args[0]).getName());
            if (value == null && method.equals("getRequiredProperty")) {
                throw new GuacamoleServerException("Missing required property");
            }
            return value;
        }
        throw new AssertionError("Unexpected configuration operation: " + method);
    });
    private final SebValidation validation = new SebValidation(environment,
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    private final SebValidationResource resource = new SebValidationResource(validation);

    SebValidationSecurityTest() {
        properties.put("seb-config-keys", KEY);
        properties.put("seb-required-user-groups", "students");
    }

    private interface Invocation { Object call(String method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (object, method, args) -> invocation.call(method.getName(), args)));
    }

    private String hash(String page) throws Exception {
        return SebValidation.calculateRequestHash(page, KEY);
    }

    private void login() throws Exception {
        Credentials credentials = new Credentials("student", "password", request);
        AbstractAuthenticatedUser user = new AbstractAuthenticatedUser(true) {
            public AuthenticationProvider getAuthenticationProvider() { return null; }
            public Credentials getCredentials() { return credentials; }
            public Set<String> getEffectiveUserGroups() { return Set.of("students"); }
        };
        new SebValidationListener(environment, validation).handleEvent(new AuthenticationSuccessEvent(user));
    }

    @Test
    void headerAuthenticationIncludesQueryString() throws Exception {
        query = "a=1&b=%2F";
        header = hash(PAGE + "api/tokens?" + query).toUpperCase();
        assertTrue(validation.validateHeader(request));
        assertDoesNotThrow(this::login);
        query = "a=2";
        assertFalse(validation.validateHeader(request));
        assertThrows(GuacamoleSecurityException.class, this::login);
        for (String invalid : new String[] {null, "bad", "0".repeat(64)}) {
            header = invalid;
            assertFalse(validation.validateHeader(request));
        }
    }

    @Test
    void initialLoginRequiresHandshakeThenSessionLoginSucceeds() throws Exception {
        assertThrows(GuacamoleSecurityException.class, this::login);
        Response response = resource.validate(hash(PAGE), PAGE, "SEB test", request);
        assertEquals(200, response.getStatus());
        assertEquals("{\"accepted\":true,\"maxAgeSeconds\":300}", response.getEntity());
        assertTrue(response.getHeaderString("Cache-Control").contains("no-store"));
        assertEquals(NOW, attributes.get(SebValidation.SESSION_VALIDATED_AT));
        assertEquals("SEB test", attributes.get(SebValidation.SESSION_VERSION));
        assertDoesNotThrow(this::login);
    }

    @Test
    void rejectedHandshakeRevokesApprovalAndPreservesOtherAttributes() throws Exception {
        resource.validate(hash(PAGE), PAGE, "SEB", request);
        attributes.put("unrelated", "keep");
        assertEquals(403, resource.validate("bad", PAGE, "SEB", request).getStatus());
        assertFalse(attributes.containsKey(SebValidation.SESSION_VALIDATED_AT));
        assertFalse(attributes.containsKey(SebValidation.SESSION_VERSION));
        assertEquals("keep", attributes.get("unrelated"));
        assertThrows(GuacamoleSecurityException.class, this::login);
        assertEquals(1, createdSessions);
    }

    @Test
    void rejectionAndConfigurationErrorsDoNotCreateSessions() throws Exception {
        assertEquals(403, resource.validate("bad", PAGE, null, request).getStatus());
        properties.put("seb-config-keys", "bad");
        assertThrows(GuacamoleServerException.class, () -> resource.validate(hash(PAGE), PAGE, null, request));
        assertEquals(0, createdSessions);
    }

    @Test
    void configurationErrorsRevokeEarlierApproval() throws Exception {
        for (String name : new String[] {"seb-config-keys", "seb-validation-max-age-seconds"}) {
            properties.put("seb-config-keys", KEY);
            properties.remove("seb-validation-max-age-seconds");
            resource.validate(hash(PAGE), PAGE, null, request);
            attributes.put("unrelated", "keep");
            properties.put(name, "bad");
            assertThrows(GuacamoleServerException.class, () -> resource.validate(hash(PAGE), PAGE, null, request));
            assertEquals(Map.of("unrelated", "keep"), attributes);
            assertThrows(GuacamoleSecurityException.class, this::login);
        }
    }

    @Test
    void unexpectedValidationExceptionAlsoRevokesApproval() throws Exception {
        resource.validate(hash(PAGE), PAGE, null, request);
        Environment brokenEnvironment = proxy(Environment.class, (method, args) -> {
            throw new IllegalStateException("Configuration unavailable");
        });
        SebValidationResource broken = new SebValidationResource(
                new SebValidation(brokenEnvironment, Clock.systemUTC()));
        assertThrows(IllegalStateException.class, () -> broken.validate(hash(PAGE), PAGE, null, request));
        assertTrue(attributes.isEmpty());
        assertThrows(GuacamoleSecurityException.class, this::login);
    }

    @Test
    void pageRestrictionsAndDefaultPorts() throws Exception {
        for (String page : new String[] {PAGE, PAGE + "exam?x=1", "https://exam.test:443/guacamole"}) {
            assertTrue(validation.validateJavaScriptValue(request, page, hash(page)), page);
        }
        for (String page : new String[] {"https://other.test/guacamole/", "http://exam.test/guacamole/",
                "https://exam.test:444/guacamole/", "https://exam.test/guacamole-other/",
                "https://exam.test/elsewhere", PAGE + "#fragment", "not a uri", "/guacamole/"}) {
            assertFalse(validation.validateJavaScriptValue(request, page, hash(page)), page);
        }
        assertFalse(validation.validateJavaScriptValue(request, null, hash(PAGE)));
        assertFalse(validation.validateJavaScriptValue(request, PAGE, "wrong"));
        assertFalse(validation.validateJavaScriptValue(request, PAGE, "0".repeat(64)));
        scheme = "http"; port = 80;
        String http = "http://exam.test/guacamole/";
        assertTrue(validation.validateJavaScriptValue(request, http, hash(http)));
    }

    @Test
    void sessionExpirationBoundariesAndMissingAttributes() throws Exception {
        properties.put("seb-validation-max-age-seconds", "30");
        assertFalse(validation.isSessionValidated(null));
        assertFalse(validation.isSessionValidated(session));
        attributes.put(SebValidation.SESSION_VALIDATED_AT, "10000");
        assertFalse(validation.isSessionValidated(session));
        for (long age : new long[] {0, 29, 30}) {
            attributes.put(SebValidation.SESSION_VALIDATED_AT, NOW - age);
            assertTrue(validation.isSessionValidated(session));
        }
        for (long age : new long[] {-1, 31}) {
            attributes.put(SebValidation.SESSION_VALIDATED_AT, NOW - age);
            assertFalse(validation.isSessionValidated(session));
        }
    }

    @Test
    void lifetimeConfigurationBoundsAndDefault() throws Exception {
        for (String value : new String[] {null, "", "  "}) {
            properties.put("seb-validation-max-age-seconds", value);
            assertEquals(300, validation.getMaxAgeSeconds());
        }
        for (String value : new String[] {"30", " 300 ", "3600"}) {
            properties.put("seb-validation-max-age-seconds", value);
            assertEquals(Long.parseLong(value.trim()), validation.getMaxAgeSeconds());
            assertTrue(resource.validate(hash(PAGE), PAGE, null, request).getEntity().toString()
                    .contains("\"maxAgeSeconds\":" + value.trim()));
        }
        for (String value : new String[] {"29", "3601", "-1", "abc", "30.5", "9999999999999999999999"}) {
            properties.put("seb-validation-max-age-seconds", value);
            assertThrows(GuacamoleServerException.class, validation::getMaxAgeSeconds);
        }
    }

    @Test
    void configKeysNormalizeWhitespaceAndRejectEmptyEntries() throws Exception {
        properties.put("seb-config-keys", "  " + "1".repeat(64) + " , " + KEY.toUpperCase() + "  ");
        assertTrue(validation.validateJavaScriptValue(request, PAGE, hash(PAGE)));
        for (String value : new String[] {"", " ", ",", KEY + ",", "," + KEY, KEY + ", ," + KEY,
                KEY + ",invalid", "z".repeat(64), "a".repeat(63)}) {
            properties.put("seb-config-keys", value);
            assertThrows(GuacamoleServerException.class,
                    () -> validation.validateJavaScriptValue(request, PAGE, hash(PAGE)), value);
        }
        properties.remove("seb-config-keys");
        assertThrows(GuacamoleServerException.class,
                () -> validation.validateJavaScriptValue(request, PAGE, hash(PAGE)));
    }
}
