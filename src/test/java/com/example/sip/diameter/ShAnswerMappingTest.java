package com.example.sip.diameter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShAnswerMappingTest {

    @Test
    void mapsKnownResultCodes() {
        assertEquals(ShAnswer.Outcome.SUCCESS, ShAnswer.mapOutcome(ShConstants.RESULT_SUCCESS));
        assertEquals(ShAnswer.Outcome.USER_UNKNOWN, ShAnswer.mapOutcome(ShConstants.RESULT_USER_UNKNOWN));
        assertEquals(ShAnswer.Outcome.UNABLE_TO_DELIVER, ShAnswer.mapOutcome(ShConstants.RESULT_UNABLE_TO_DELIVER));
        assertEquals(ShAnswer.Outcome.REALM_NOT_SERVED, ShAnswer.mapOutcome(ShConstants.RESULT_REALM_NOT_SERVED));
        assertEquals(ShAnswer.Outcome.ERROR, ShAnswer.mapOutcome(5012L));
    }
}
