package com.shanshui.apmserver;

import com.shanshui.apmserver.service.InvalidAppInputException;
import com.shanshui.apmserver.service.AppInputValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppInputValidatorTests {

    @Test
    void trimsNameAndDescription() {
        assertEquals("项目一", AppInputValidator.normalizeName(" 项目一 "));
        assertEquals("说明", AppInputValidator.normalizeDescription(" 说明 "));
    }

    @Test
    void trimsLowercaseApplicationIdWithoutChangingIt() {
        assertEquals("com.example_app.mobile2",
                AppInputValidator.normalizePackageName(" com.example_app.mobile2 "));
    }

    @Test
    void acceptsBoundaryLengthApplicationId() {
        String applicationId = "a." + "b".repeat(253);
        assertEquals(255, AppInputValidator.normalizePackageName(applicationId).length());
    }

    @Test
    void rejectsMissingSingleSegmentIllegalAndOverlongApplicationIds() {
        assertThrows(InvalidAppInputException.class,
                () -> AppInputValidator.normalizePackageName(null));
        assertThrows(InvalidAppInputException.class,
                () -> AppInputValidator.normalizePackageName("example"));
        assertThrows(InvalidAppInputException.class,
                () -> AppInputValidator.normalizePackageName("com.1example.app"));
        assertThrows(InvalidAppInputException.class,
                () -> AppInputValidator.normalizePackageName("com.Example.app"));
        assertThrows(InvalidAppInputException.class,
                () -> AppInputValidator.normalizePackageName("com.example-app"));
        assertThrows(InvalidAppInputException.class,
                () -> AppInputValidator.normalizePackageName("a." + "b".repeat(254)));
    }
}
