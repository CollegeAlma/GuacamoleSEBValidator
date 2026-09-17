package org.collegealma.guacamole_seb_validator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.guacamole.GuacamoleSecurityException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.net.auth.AbstractAuthenticatedUser;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.junit.jupiter.api.Test;

class SebValidationListenerTest {

    private SebValidationListener listener(String groups) {
        Environment environment = (Environment) Proxy.newProxyInstance(
                Environment.class.getClassLoader(), new Class<?>[] { Environment.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getProperty")
                            && args[0] == SebProperties.REQUIRED_USER_GROUPS) {
                        return groups;
                    }
                    throw new AssertionError("Unexpected configuration access: " + method);
                });
        return new SebValidationListener(environment);
    }

    private AuthenticationSuccessEvent event(Set<String> groups, Credentials credentials) {
        return new AuthenticationSuccessEvent(new AbstractAuthenticatedUser(true) {
            @Override
            public AuthenticationProvider getAuthenticationProvider() {
                return null;
            }

            @Override
            public Credentials getCredentials() {
                return credentials;
            }

            @Override
            public Set<String> getEffectiveUserGroups() {
                return groups;
            }
        });
    }

    @Test
    void unrelatedEventsDoNotReadConfiguration() {
        assertDoesNotThrow(() -> listener(",").handleEvent(new Object()));
    }

    @Test
    void missingOrBlankGroupListDisablesEnforcement() {
        for (String configured : new String[] { null, "", "  " }) {
            assertDoesNotThrow(() -> listener(configured).handleEvent(event(Set.of("students"), null)));
        }
    }

    @Test
    void nonMembersBypassEvenWithoutHttpCredentials() {
        assertDoesNotThrow(() -> listener("students").handleEvent(event(Set.of("staff"), null)));
        assertDoesNotThrow(() -> listener("students").handleEvent(event(Set.of(), null)));
    }

    @Test
    void anyMatchingGroupRequiresValidation() {
        assertThrows(GuacamoleSecurityException.class, () -> listener("students, exam-candidates")
                .handleEvent(event(Set.of("staff", "exam-candidates"), null)));
    }

    @Test
    void whitespaceAndDuplicateGroupsAreSupported() {
        assertThrows(GuacamoleSecurityException.class, () -> listener(" students , students ")
                .handleEvent(event(Set.of("students"), null)));
    }

    @Test
    void matchingIsExactAndCaseSensitive() {
        assertDoesNotThrow(() -> listener("students")
                .handleEvent(event(Set.of("Students", "students-extra"), null)));
    }

    @Test
    void malformedListsAreRejected() {
        for (String configured : new String[] { ",", "students,", ",students", "students, ,staff" }) {
            assertThrows(GuacamoleServerException.class,
                    () -> listener(configured).handleEvent(event(Set.of("staff"), null)));
        }
    }

    @Test
    void selectedUserWithOrdinaryBrowserIsRejected() {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class },
                (proxy, method, args) -> null);
        Credentials credentials = new Credentials("student", "password", request);
        assertThrows(GuacamoleSecurityException.class,
                () -> listener("students").handleEvent(event(Set.of("students"), credentials)));
    }
}
