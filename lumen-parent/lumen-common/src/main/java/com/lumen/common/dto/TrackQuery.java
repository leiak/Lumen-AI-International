package com.lumen.common.dto;

/**
 * 通用追踪查询 (Iteration 4.5A).
 *
 * <p>lumen-customer 的 PortalTrackController + lumen-dispatch 的 DeliveryOrderService.pageForCustomer 共享.
 * 放 lumen-common 避免 lumen-dispatch → lumen-customer 的循环依赖.
 */
public record TrackQuery(String doNo, String status, int page, int size) {
    public TrackQuery {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 100) size = 100;
    }
}