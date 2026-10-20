package com.lumen.customer.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PortalCreateOrderRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setup() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void validRequest_passesValidation() {
        PortalCreateOrderRequest req = new PortalCreateOrderRequest(
            1L, "addr1", "addr2",
            new BigDecimal("10"), new BigDecimal("0.5"), 1,
            LocalDateTime.now().plusHours(3), null, null);
        Set<ConstraintViolation<PortalCreateOrderRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "expected no violations, got: " + violations);
    }

    @Test
    void negativeWeight_violatesPositive() {
        PortalCreateOrderRequest req = new PortalCreateOrderRequest(
            1L, "addr1", "addr2",
            new BigDecimal("-1"), new BigDecimal("0.5"), 1,
            LocalDateTime.now().plusHours(3), null, null);
        Set<ConstraintViolation<PortalCreateOrderRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("totalWeightKg")));
    }

    @Test
    void blankShipperAddress_violatesNotBlank() {
        PortalCreateOrderRequest req = new PortalCreateOrderRequest(
            1L, "", "addr2",
            new BigDecimal("10"), new BigDecimal("0.5"), 1,
            LocalDateTime.now().plusHours(3), null, null);
        Set<ConstraintViolation<PortalCreateOrderRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("shipperAddress")));
    }

    @Test
    void nullPickupWindowStart_violatesNotNull() {
        PortalCreateOrderRequest req = new PortalCreateOrderRequest(
            1L, "addr1", "addr2",
            new BigDecimal("10"), new BigDecimal("0.5"), 1,
            null, null, null);
        Set<ConstraintViolation<PortalCreateOrderRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("plannedPickupWindowStart")));
    }
}
