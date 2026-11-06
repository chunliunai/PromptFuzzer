package com.promptfuzzer.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BrowserTargetProfileResponseTest {

    @Test
    void missingProfileReturnsUnknownPlaceholder() {
        BrowserTargetProfileResponse response = BrowserTargetProfileResponse.missing("default");

        assertNull(response.getId());
        assertEquals("default", response.getTargetType());
        assertEquals("UNKNOWN", response.getAuthState());
        assertEquals("browser-auth/default-profile", response.getProfilePath());
    }
}
