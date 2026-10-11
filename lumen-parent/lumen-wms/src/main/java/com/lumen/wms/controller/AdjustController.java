package com.lumen.wms.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.wms.dto.AddInventoryAdjustLineRequest;
import com.lumen.wms.dto.CreateInventoryAdjustRequest;
import com.lumen.wms.dto.InventoryAdjustVO;
import com.lumen.wms.dto.RejectAdjustRequest;
import com.lumen.wms.dto.UpdateInventoryAdjustRequest;
import com.lumen.wms.entity.WmsInventoryAdjust;
import com.lumen.wms.entity.WmsInventoryAdjustLine;
import com.lumen.wms.service.InventoryAdjustService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存调整单 REST 控制器 (PR5-T5.2).
 *
 * <p>覆盖调整单基础 CRUD + 行项目追加/删除 + 状态机动作
 * (submit / approve / reject / execute / cancel).
 *
 * <p>状态机路径：DRAFT → (PENDING_APPROVAL →) APPROVED → DONE / REJECTED / CANCELLED。
 * {@code submit} 同时按阈值评估金额 + 数量，任一维度超过阈值则走审批.
 * {@code execute} 对每行调用 {@code inventoryService.adjustDelta()} 落地库存账本.
 * 写入操作走 {@link Audit} 记录审计日志.
 */
@RestController
@RequestMapping("/api/v1/wms/inventory-adjusts")
@RequiredArgsConstructor
public class AdjustController {

    private final InventoryAdjustService service;

    /**
     * 分页查询调整单列表.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('wms:adjust:list')")
    public R<PageResult<WmsInventoryAdjust>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "20") long pageSize,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Long warehouseId,
                                                  @RequestParam(required = false) String status) {
        return R.ok(service.page(pageNum, pageSize, keyword, warehouseId, status));
    }

    /**
     * 查询调整单详情 (含行项目).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:adjust:list')")
    public R<InventoryAdjustVO> get(@PathVariable Long id) {
        WmsInventoryAdjust note = service.getById(id);
        InventoryAdjustVO vo = service.toVO(note);
        List<WmsInventoryAdjustLine> lines = service.listLines(id);
        vo.setLines(service.lineVOs(lines));
        return R.ok(vo);
    }

    /**
     * 新建调整单 (一次性下发全部行项目，每行必须带 locationId + deltaQty).
     */
    @PostMapping
    @PreAuthorize("hasAuthority('wms:adjust:create')")
    @Audit(action = "create", resource = "wms-adjust")
    public R<WmsInventoryAdjust> create(@Valid @RequestBody CreateInventoryAdjustRequest req) {
        return R.ok(service.create(req));
    }

    /**
     * 更新调整单主表 (warehouseId / reason / remark,可选整量替换行项目).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:adjust:update')")
    @Audit(action = "update", resource = "wms-adjust")
    public R<WmsInventoryAdjust> update(@PathVariable Long id,
                                        @Valid @RequestBody UpdateInventoryAdjustRequest req) {
        return R.ok(service.update(id, req));
    }

    /**
     * 逻辑删除调整单 (级联删除其下属行).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:adjust:update')")
    @Audit(action = "delete", resource = "wms-adjust")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok(null);
    }

    /**
     * 追加单行到调整单.
     */
    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('wms:adjust:update')")
    @Audit(action = "add-line", resource = "wms-adjust")
    public R<WmsInventoryAdjustLine> addLine(@PathVariable Long id,
                                             @Valid @RequestBody AddInventoryAdjustLineRequest req) {
        return R.ok(service.addLine(id, req));
    }

    /**
     * 删除调整单指定行.
     */
    @DeleteMapping("/{id}/lines/{lid}")
    @PreAuthorize("hasAuthority('wms:adjust:update')")
    @Audit(action = "remove-line", resource = "wms-adjust")
    public R<Void> removeLine(@PathVariable Long id, @PathVariable Long lid) {
        service.removeLine(id, lid);
        return R.ok(null);
    }

    /**
     * 提交调整单 — 按阈值路由到 PENDING_APPROVAL 或 APPROVED.
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('wms:adjust:submit')")
    @Audit(action = "submit", resource = "wms-adjust")
    public R<WmsInventoryAdjust> submit(@PathVariable Long id) {
        return R.ok(service.submit(id));
    }

    /**
     * 审批调整单 — PENDING_APPROVAL → APPROVED.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('wms:adjust:approve')")
    @Audit(action = "approve", resource = "wms-adjust")
    public R<WmsInventoryAdjust> approve(@PathVariable Long id) {
        return R.ok(service.approve(id));
    }

    /**
     * 驳回调整单 — PENDING_APPROVAL → REJECTED, reason 写入单据 reject_reason.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('wms:adjust:approve')")
    @Audit(action = "reject", resource = "wms-adjust")
    public R<WmsInventoryAdjust> reject(@PathVariable Long id,
                                        @Valid @RequestBody RejectAdjustRequest req) {
        return R.ok(service.reject(id, req.getReason()));
    }

    /**
     * 执行调整单 — APPROVED → DONE,对每行调用 inventoryService.adjustDelta() 应用 deltaQty.
     */
    @PostMapping("/{id}/execute")
    @PreAuthorize("hasAuthority('wms:adjust:execute')")
    @Audit(action = "execute", resource = "wms-adjust")
    public R<WmsInventoryAdjust> execute(@PathVariable Long id) {
        return R.ok(service.execute(id));
    }

    /**
     * 取消调整单 — DRAFT / PENDING_APPROVAL / APPROVED → CANCELLED;
     * DONE / REJECTED / CANCELLED 状态拒绝取消.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('wms:adjust:cancel')")
    @Audit(action = "cancel", resource = "wms-adjust")
    public R<WmsInventoryAdjust> cancel(@PathVariable Long id) {
        return R.ok(service.cancel(id));
    }
}
