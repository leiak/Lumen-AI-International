package com.lumen.finance.dto.portal;

import java.util.List;

/**
 * 商家门户分页结果 (Iteration 4.5C-2).
 *
 * <p><b>不复用</b> {@code com.lumen.common.api.PageResult}: 门户契约的字段名是
 * {@code page} / {@code size}, 而 {@code PageResult} 用的是 {@code pageNum} /
 * {@code pageSize}。门户自己定义一份, 前端拿到的 JSON 字段名就是它要的形状,
 * 不用在每个 controller 里再写一层改名。
 *
 * <p>字段名 {@code records} 而非 MyBatis-Plus 的 {@code current}/{@code size},
 * 免得调用方误以为这是 {@code IPage} 的直接投影。
 *
 * @param records 当前页数据
 * @param total   总条数 (前端分页器直接用)
 * @param page    当前页码, 从 1 开始
 * @param size    每页条数
 */
public record MerchantPage<T>(List<T> records, long total, long page, long size) {

    public static <T> MerchantPage<T> of(List<T> records, long total, long page, long size) {
        return new MerchantPage<>(records, total, page, size);
    }

    /** 空结果 — 归属集为空时直接返回, 省掉一次 in () 查询。 */
    public static <T> MerchantPage<T> empty(long page, long size) {
        return new MerchantPage<>(List.of(), 0L, page, size);
    }
}