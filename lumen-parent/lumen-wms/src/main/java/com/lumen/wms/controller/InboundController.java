package com.lumen.wms.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.wms.dto.AddInboundLineRequest;
import com.lumen.wms.dto.CreateInboundNoteRequest;
import com.lumen.wms.dto.InboundLineVO;
import com.lumen.wms.dto.InboundNoteVO;
import com.lumen.wms.dto.PutawayInboundLineRequest;
import com.lumen.wms.dto.ReceiveInboundLineRequest;
import com.lumen.wms.dto.UpdateInboundNoteRequest;
import com.lumen.wms.entity.WmsInboundLine;
import com.lumen.wms.entity.WmsInboundNote;
import com.lumen.wms.service.InboundService;
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
 * 入库单 REST 控制器 (PR3-T3.5).
 *
 * <p>覆盖入库单基础 CRUD + 行项目追加/删除 + 状态机动作
 * (submit / approve / cancel / receive / putaway).
 * 写入操作走 {@link Audit} 记录审计日志.
 */
@RestController
@RequestMapping("/api/v1/wms/inbound-notes")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService svc;

    /**
     * 分页查询入库单列表.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('wms:inbound:list')")
    public R<PageResult<WmsInboundNote>> page(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "20") long pageSize,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status) {
        return R.ok(svc.page(pageNum, pageSize, keyword, status));
    }

    /**
     * 查询入库单详情 (含行项目).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:inbound:list')")
    public R<InboundNoteVO> get(@PathVariable Long id) {
        WmsInboundNote note = svc.getById(id);
        InboundNoteVO vo = svc.toVO(note);
        List<WmsInboundLine> lines = svc.listLines(id);
        vo.setLines(svc.lineVOs(lines));
        return R.ok(vo);
    }

    /**
     * 新建入库单 (一次性下发全部行项目).
     */
    @PostMapping
    @PreAuthorize("hasAuthority('wms:inbound:create')")
    @Audit(action = "create", resource = "wms-inbound")
    public R<WmsInboundNote> create(@Valid @RequestBody CreateInboundNoteRequest req) {
        return R.ok(svc.create(req));
    }

    /**
     * 更新入库单主表 (可选整量替换行项目).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:inbound:update')")
    @Audit(action = "update", resource = "wms-inbound")
    public R<WmsInboundNote> update(@PathVariable Long id, @Valid @RequestBody UpdateInboundNoteRequest req) {
        return R.ok(svc.update(id, req));
    }

    /**
     * 逻辑删除入库单 (级联删除其下属行).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:inbound:update')")
    @Audit(action = "delete", resource = "wms-inbound")
    public R<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return R.ok(null);
    }

    /**
     * 追加单行到入库单.
     */
    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('wms:inbound:update')")
    @Audit(action = "add-line", resource = "wms-inbound")
    public R<WmsInboundLine> addLine(@PathVariable Long id, @Valid @RequestBody AddInboundLineRequest req) {
        return R.ok(svc.addLine(id, req));
    }

    /**
     * 删除入库单指定行.
     */
    @DeleteMapping("/{id}/lines/{lid}")
    @PreAuthorize("hasAuthority('wms:inbound:update')")
    @Audit(action = "remove-line", resource = "wms-inbound")
    public R<Void> removeLine(@PathVariable Long id, @PathVariable Long lid) {
        svc.removeLine(id, lid);
        return R.ok(null);
    }

    /**
     * 提交入库单 — 按阈值路由到 PENDING_APPROVAL 或 RECEIVING.
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('wms:inbound:submit')")
    @Audit(action = "submit", resource = "wms-inbound")
    public R<WmsInboundNote> submit(@PathVariable Long id) {
        return R.ok(svc.submit(id));
    }

    /**
     * 审批入库单 — PENDING_APPROVAL → RECEIVING.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('wms:inbound:approve')")
    @Audit(action = "approve", resource = "wms-inbound")
    public R<Void> approve(@PathVariable Long id) {
        svc.approve(id);
        return R.ok(null);
    }

    /**
     * 取消入库单 (任意非终态 → CANCELLED).
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('wms:inbound:cancel')")
    @Audit(action = "cancel", resource = "wms-inbound")
    public R<Void> cancel(@PathVariable Long id) {
        svc.cancel(id);
        return R.ok(null);
    }

    /**
     * 收货 (增量累加 receivedQty) — 收齐所有行后自动翻转为 PUTAWAY.
     */
    @PostMapping("/{id}/lines/{lid}/receive")
    @PreAuthorize("hasAuthority('wms:inbound:putaway')")
    @Audit(action = "receive", resource = "wms-inbound")
    public R<WmsInboundLine> receive(@PathVariable Long id, @PathVariable Long lid,
                                     @Valid @RequestBody ReceiveInboundLineRequest req) {
        return R.ok(svc.receiveLine(id, lid, req.getQty()));
    }

    /**
     * 上架 (增量累加 putawayQty + 增加库存余额) — 上架完所有行后自动翻转为 DONE.
     */
    @PostMapping("/{id}/lines/{lid}/putaway")
    @PreAuthorize("hasAuthority('wms:inbound:putaway')")
    @Audit(action = "putaway", resource = "wms-inbound")
    public R<WmsInboundLine> putaway(@PathVariable Long id, @PathVariable Long lid,
                                     @Valid @RequestBody PutawayInboundLineRequest req) {
        return R.ok(svc.putawayLine(id, lid, req.getQty(), req.getLocationId()));
    }
}