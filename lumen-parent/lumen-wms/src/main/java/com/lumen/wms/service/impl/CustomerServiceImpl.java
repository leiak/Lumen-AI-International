package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.CreateCustomerRequest;
import com.lumen.wms.dto.CustomerVO;
import com.lumen.wms.dto.UpdateCustomerRequest;
import com.lumen.wms.entity.WmsCustomer;
import com.lumen.wms.exception.CustomerNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsCustomerMapper;
import com.lumen.wms.service.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户主数据服务实现.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final WmsCustomerMapper mapper;

    @Override
    public PageResult<WmsCustomer> page(long pageNum, long pageSize, String keyword) {
        Page<WmsCustomer> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsCustomer> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(WmsCustomer::getName, keyword).or().like(WmsCustomer::getCode, keyword));
        }
        qw.orderByDesc(WmsCustomer::getId);
        Page<WmsCustomer> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    public WmsCustomer getById(Long id) {
        WmsCustomer c = mapper.selectById(id);
        if (c == null) throw new CustomerNotFoundException(id);
        return c;
    }

    @Override
    @Transactional
    public WmsCustomer create(CreateCustomerRequest req) {
        WmsCustomer entity = new WmsCustomer();
        BeanUtils.copyProperties(req, entity);
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.require());
        }
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate customer code: {}", req.getCode());
            throw new WmsDuplicateCodeException("customer", req.getCode());
        }
        return entity;
    }

    @Override
    @Transactional
    public WmsCustomer update(Long id, UpdateCustomerRequest req) {
        WmsCustomer existing = getById(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate customer code on update: {}", req.getCode());
            throw new WmsDuplicateCodeException("customer", req.getCode());
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
    }

    @Override
    public CustomerVO toVO(WmsCustomer entity) {
        if (entity == null) return null;
        CustomerVO vo = new CustomerVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}