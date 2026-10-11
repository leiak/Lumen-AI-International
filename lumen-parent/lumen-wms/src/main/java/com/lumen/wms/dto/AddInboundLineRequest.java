package com.lumen.wms.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 入库单追加行请求体.
 *
 * <p>{@code noteId} 由路径传入；{@code lineNo} 不必显式提供 — 服务层按现有最大 + 1 自动分配.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AddInboundLineRequest extends InboundLineDto {

    /**
     * 可选：调用方显式指定行号；为 null 时服务层自动分配.
     */
    private Integer lineNo;
}
