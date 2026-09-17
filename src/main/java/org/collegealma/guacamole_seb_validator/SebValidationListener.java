package org.collegealma.guacamole_seb_validator;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleSecurityException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.environment.LocalEnvironment;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.apache.guacamole.net.event.listener.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SebValidationListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SebValidationListener.class);

    private final Environment environment;
    private final SebValidation validation;

    public SebValidationListener() {
        this(null);
    }

    SebValidationListener(Environment environment) {
        this(environment, new SebValidation(environment, java.time.Clock.systemUTC()));
    }

    SebValidationListener(Environment environment, SebValidation validation) {
        this.environment = environment;
        this.validation = validation;
    }

    private boolean requiresValidation(AuthenticatedUser user) throws GuacamoleException {
        Environment configuration = environment != null ? environment : LocalEnvironment.getInstance();
        String configured = configuration.getProperty(SebProperties.REQUIRED_USER_GROUPS);
        if (configured == null || configured.trim().isEmpty()) {
            return false;
        }

        Set<String> requiredGroups = new HashSet<>();
        for (String value : configured.split(",", -1)) {
            String group = value.trim();
            if (group.isEmpty()) {
                throw new GuacamoleServerException(
                        "seb-required-user-groups must be a comma-separated list of non-empty group identifiers.");
            }
            requiredGroups.add(group);
        }

        for (String group : user.getEffectiveUserGroups()) {
            if (requiredGroups.contains(group)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handleEvent(Object event) throws GuacamoleException {
        if (!(event instanceof AuthenticationSuccessEvent)) {
            return;
        }

        AuthenticationSuccessEvent success = (AuthenticationSuccessEvent) event;
        if (!requiresValidation(success.getAuthenticatedUser())) {
            return;
        }

        Credentials credentials = success.getCredentials();

        if (credentials == null) {
            deny("No HTTP credentials were associated with the authentication request.");
        }

        // getRequest() is safe here because the listener runs synchronously within
        // the authentication request. The reference is never retained.
        @SuppressWarnings("deprecation")
        HttpServletRequest request = credentials.getRequest();

        if (request == null) {
            deny("The authentication request is unavailable.");
        }

        if (validation.validateHeader(request)) {
            LOGGER.info("SEB header validation accepted for Guacamole user {}", credentials.getUsername());

            return;
        }

        if (validation.isSessionValidated(credentials.getSession())) {
            LOGGER.info("SEB JavaScript API validation accepted for Guacamole user {}", credentials.getUsername());

            return;
        }

        deny("Safe Exam Browser validation is required.");
    }

    private static void deny(String reason) throws GuacamoleSecurityException {
        LOGGER.warn("Guacamole authentication rejected: {}", reason);

        throw new GuacamoleSecurityException("This Guacamole server requires the authorized Safe Exam Browser configuration.");
    }
}
