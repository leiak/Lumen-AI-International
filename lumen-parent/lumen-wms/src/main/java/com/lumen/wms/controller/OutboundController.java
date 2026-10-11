package com.lumen.wms.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.wms.dto.AddOutboundLineRequest;
import com.lumen.wms.dto.CreateOutboundNoteRequest;
import com.lumen.wms.dto.OutboundLineVO;
import com.lumen.wms.dto.OutboundNoteVO;
import com.lumen.wms.dto.PickOutboundLineRequest;
import com.lumen.wms.dto.ShipOutboundRequest;
import com.lumen.wms.dto.UpdateOutboundNoteRequest;
import com.lumen.wms.entity.WmsOutboundLine;
import com.lumen.wms.entity.WmsOutboundNote;
import com.lumen.wms.service.OutboundService;
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
 * 出库单 REST 控制器 (PR4-T4.3).
 *
 * <p>覆盖出库单基础 CRUD + 行项目追加/删除 + 状态机动作
 * (submit / approve / pick / ship / complete / cancel).
 *
 * <p>状态机路径：DRAFT → (PENDING_APPROVAL →) PICKING → REVIEW → SHIPPED → DONE.
 * {@code submit} 同时按阈值评估 + 对每行执行库存锁预留 (lockQty).
 * {@code complete} 时 flushLock 把锁库存核销为消耗.
 * 写入操作走 {@link Audit} 记录审计日志.
 */
@RestController
@RequestMapping("/api/v1/wms/outbound-notes")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService svc;

    /**
     * 分页查询出库单列表.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('wms:outbound:list')")
    public R<PageResult<WmsOutboundNote>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "20") long pageSize,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return R.ok(svc.page(pageNum, pageSize, keyword, status));
    }

    /**
     * 查询出库单详情 (含行项目).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:outbound:list')")
    public R<OutboundNoteVO> get(@PathVariable Long id) {
        WmsOutboundNote note = svc.getById(id);
        OutboundNoteVO vo = svc.toVO(note);
        List<WmsOutboundLine> lines = svc.listLines(id);
        vo.setLines(svc.lineVOs(lines));
        return R.ok(vo);
    }

    /**
     * 新建出库单 (一次性下发全部行项目，每行必须带 sourceLocationId).
     */
    @PostMapping
    @PreAuthorize("hasAuthority('wms:outbound:create')")
    @Audit(action = "create", resource = "wms-outbound")
    public R<WmsOutboundNote> create(@Valid @RequestBody CreateOutboundNoteRequest req) {
        return R.ok(svc.create(req));
    }

    /**
     * 更新出库单主表 (carrier / trackingNo / remark，可选整量替换行项目).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:outbound:update')")
    @Audit(action = "update", resource = "wms-outbound")
    public R<WmsOutboundNote> update(@PathVariable Long id, @Valid @RequestBody UpdateOutboundNoteRequest req) {
        return R.ok(svc.update(id, req));
    }

    /**
     * 逻辑删除出库单 (级联删除其下属行).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:outbound:update')")
    @Audit(action = "delete", resource = "wms-outbound")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok(null);
    }

    /**
     * 追加单行到出库单.
     */
    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('wms:outbound:update')")
    @Audit(action = "add-line", resource = "wms-outbound")
    public R<WmsOutboundLine> addLine(@PathVariable Long id, @Valid @RequestBody AddOutboundLineRequest req) {
        return R.ok(svc.addLine(id, req));
    }

    /**
     * 删除出库单指定行.
     */
    @DeleteMapping("/{id}/lines/{lid}")
    @PreAuthorize("hasAuthority('wms:outbound:update')")
    @Audit(action = "remove-line", resource = "wms-outbound")
    public R<Void> removeLine(@PathVariable Long id, @PathVariable Long lid) {
        svc.removeLine(id, lid);
        return R.ok(null);
    }

    /**
     * 提交出库单 — 按阈值路由到 PENDING_APPROVAL 或 PICKING，同时锁库存.
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('wms:outbound:submit')")
    @Audit(action = "submit", resource = "wms-outbound")
    public R<WmsOutboundNote> submit(@PathVariable Long id) {
        return R.ok(svc.submit(id));
    }

    /**
     * 审批出库单 — PENDING_APPROVAL → PICKING.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('wms:outbound:approve')")
    @Audit(action = "approve", resource = "wms-outbound")
    public R<Void> approve(@PathVariable Long id) {
        svc.approve(id);
        return R.ok(null);
    }

    /**
     * 拣货行项目 — 增量累加 pickedQty + 释放对应锁库存；收齐所有行后翻转为 REVIEW.
     */
    @PostMapping("/{id}/lines/{lid}/pick")
    @PreAuthorize("hasAuthority('wms:outbound:pick')")
    @Audit(action = "pick", resource = "wms-outbound")
    public R<WmsOutboundLine> pick(@PathVariable Long id, @PathVariable Long lid,
                                   @Valid @RequestBody PickOutboundLineRequest req) {
        return R.ok(svc.pickLine(id, lid, req.getQty()));
    }

    /**
     * 发货 — REVIEW → SHIPPED + 写入 carrier / trackingNo.
     */
    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAuthority('wms:outbound:ship')")
    @Audit(action = "ship", resource = "wms-outbound")
    public R<WmsOutboundNote> ship(@PathVariable Long id, @Valid @RequestBody ShipOutboundRequest req) {
        return R.ok(svc.ship(id, req.getCarrier(), req.getTrackingNo()));
    }

    /**
     * 完成出库单 — SHIPPED → DONE + flushLock 把锁库存转为消耗 + 设置 shippedAt.
     */
    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('wms:outbound:complete')")
    @Audit(action = "complete", resource = "wms-outbound")
    public R<WmsOutboundNote> complete(@PathVariable Long id) {
        return R.ok(svc.complete(id));
    }

    /**
     * 取消出库单 — 任意非终态 → CANCELLED；PICKING/REVIEW/SHIPPED 状态下会释放未拣完的锁库存.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('wms:outbound:cancel')")
    @Audit(action = "cancel", resource = "wms-outbound")
    public R<WmsOutboundNote> cancel(@PathVariable Long id) {
        return R.ok(svc.cancel(id));
    }
}
