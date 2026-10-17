package com.lumen.finance.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinanceExceptionTest {

    @Test
    void constructor_withCode_setsMessage() {
        FinanceException ex = new FinanceException(FinanceErrorCode.RULE_NOT_FOUND);
        assertEquals("Rule not found", ex.getMessage());
        assertEquals(404, ex.getErrorCode().getHttpStatus());
    }

    @Test
    void constructor_withCodeAndMessage_overridesMessage() {
        FinanceException ex = new FinanceException(FinanceErrorCode.EXPRESSION_INVALID, "var x not declared");
        assertEquals("var x not declared", ex.getMessage());
        assertEquals("Expression invalid", ex.getErrorCode().getMessage());
    }

    @Test
    void enumValues_codesAreUnique() {
        FinanceErrorCode[] values = FinanceErrorCode.values();
        long distinctCount = java.util.Arrays.stream(values)
            .mapToInt(FinanceErrorCode::getCode)
            .distinct()
            .count();
        assertEquals(values.length, distinctCount,
            "expected all error codes to be unique, but found "
            + (values.length - distinctCount) + " duplicate(s)");
    }
}
