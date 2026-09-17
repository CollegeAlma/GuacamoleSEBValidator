package org.collegealma.guacamole_seb_validator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SebValidationTest {

    @Test
    void requestHashUsesUrlThenConfigKey() throws Exception {
        assertEquals(
                "fe39ed08c0b736050c0982c10be61ceac6c4b8a0cbc1019af8ae78bbea98dc78",
                SebValidation.calculateRequestHash(
                        "https://guacamole.example.org/guacamole/",
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
    }
}
