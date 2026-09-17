package org.collegealma.guacamole_seb_validator;

import org.apache.guacamole.properties.StringGuacamoleProperty;

final class SebProperties {

    private SebProperties() {
    }

    static final StringGuacamoleProperty REQUIRED_USER_GROUPS =
            new StringGuacamoleProperty() {
                @Override
                public String getName() {
                    return "seb-required-user-groups";
                }
            };

    static final StringGuacamoleProperty CONFIG_KEYS =
            new StringGuacamoleProperty() {
                @Override
                public String getName() {
                    return "seb-config-keys";
                }
            };

    static final StringGuacamoleProperty VALIDATION_MAX_AGE_SECONDS =
            new StringGuacamoleProperty() {
                @Override
                public String getName() {
                    return "seb-validation-max-age-seconds";
                }
            };
}
