package com.lumen.common.tenant;

public final class TenantContext {
    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    public static void set(Long tenantId) { HOLDER.set(tenantId); }
    public static Long get() { return HOLDER.get(); }
    public static Long require() {
        Long v = HOLDER.get();
        if (v == null) throw new IllegalStateException("tenant context not set");
        return v;
    }
    public static void clear() { HOLDER.remove(); }
    private TenantContext() {}
}