package org.collegealma.guacamole_seb_validator;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.AbstractAuthenticationProvider;

public class SebAuthenticationProvider extends AbstractAuthenticationProvider {
    @Override
    public String getIdentifier() {
        return "seb-validation";
    }

    @Override
    public Object getResource() throws GuacamoleException {
        return new SebValidationResource();
    }
}
