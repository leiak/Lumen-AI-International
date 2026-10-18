package com.lumen.customer.dto;

/**
 * 4.5A.3-T3.5 — Request body for {@code POST /api/auth/portal/select-customer}.
 *
 * <p>Per fix for Bug 2 (selectCustomer userId source): the controller no longer reads
 * {@code userId} from {@link com.lumen.customer.security.CustomerContext}, because
 * {@code CustomerContextFilter} only sets context for {@code /api/customer/*} paths and
 * the {@code select-customer} endpoint receives a freshly-issued portal login response
 * (not yet a Bearer token with active customer). Instead, the FE passes {@code userId}
 * through the request body, sourced from the {@link PortalLoginResponse#userId()} field
 * returned by the login step.
 *
 * <p>Decoupling from {@code CustomerContext} keeps select-customer stateless at the
 * servlet-filter level; future switch-customer flows can re-use this contract.
 */
public record PortalSelectCustomerRequest(Long userId, Long customerId) {}
