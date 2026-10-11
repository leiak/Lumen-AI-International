/**
 * Lumen Admin — WMS Item (SKU) list (PR1 vertical slice, Iteration 3.0a).
 *
 * ProTable lists `/api/v1/wms/items` (paged). ModalForm creates / edits
 * items; per-row delete calls `DELETE /wms/items/{id}`.
 *
 * Items are pure masterdata — no status column, no FK. The WmsItem entity
 * has `unitCost` (BigDecimal) which the form renders as a ProFormDigit; the
 * ProTable column formats it as a 2-decimal currency string.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormDigit,
  ProFormText,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm } from 'antd';
import { useRef, useState } from 'react';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult, WmsItem } from '@/types/api';

interface ItemFormValues {
  sku: string;
  name: string;
  uom?: string;
  barcode?: string;
  hazardClass?: string;
  unitCost?: number;
}

function formatMoney(n: number | undefined): string {
  if (n == null) return '—';
  return n.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export default function WmsItemPage() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const access = useAccess();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<WmsItem | null>(null);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/wms/items/${id}`);
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<WmsItem>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: 'SKU', dataIndex: 'sku', width: 160 },
    { title: '名称', dataIndex: 'name', width: 200 },
    { title: '单位', dataIndex: 'uom', width: 100, hideInSearch: true },
    { title: '条码', dataIndex: 'barcode', width: 160, hideInSearch: true },
    { title: '危险品类', dataIndex: 'hazardClass', width: 120, hideInSearch: true },
    {
      title: '单价',
      dataIndex: 'unitCost',
      width: 120,
      hideInSearch: true,
      render: (_, r) => formatMoney(r.unitCost),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.updatedAt} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 200,
      render: (_, record) => [
        <Button
          key="edit"
          type="link"
          size="small"
          disabled={!access.canRead('wms:item:update')}
          onClick={() => setEditing(record)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该货品?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('wms:item:delete')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<WmsItem>
        headerTitle="货品管理"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('wms:item:create')}
            onClick={() => setCreateOpen(true)}
          >
            新建货品
          </Button>,
        ]}
        request={async (params) => {
          const res = (await request.get('/wms/items', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
            },
          })) as unknown as PageResult<WmsItem> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <ModalForm<ItemFormValues>
        title="新建货品"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true }}
        onFinish={async (values) => {
          await request.post('/wms/items', values);
          message.success('已创建');
          reload();
          return true;
        }}
      >
        <ProFormText
          name="sku"
          label="SKU"
          rules={[{ required: true, message: '请输入 SKU' }]}
        />
        <ProFormText
          name="name"
          label="名称"
          rules={[{ required: true, message: '请输入货品名称' }]}
        />
        <ProFormText name="uom" label="单位" placeholder="例如: PCS / BOX / KG" />
        <ProFormText name="barcode" label="条码" />
        <ProFormText name="hazardClass" label="危险品类" />
        <ProFormDigit
          name="unitCost"
          label="单价"
          min={0}
          fieldProps={{ precision: 2, style: { width: '100%' } }}
        />
      </ModalForm>

      <ModalForm<ItemFormValues>
        title={`编辑货品 — ${editing?.name ?? ''}`}
        open={editing !== null}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={editing ?? undefined}
        onFinish={async (values) => {
          if (!editing) return false;
          await request.put(`/wms/items/${editing.id}`, values);
          message.success('已更新');
          setEditing(null);
          reload();
          return true;
        }}
      >
        <ProFormText name="sku" label="SKU" />
        <ProFormText name="name" label="名称" />
        <ProFormText name="uom" label="单位" />
        <ProFormText name="barcode" label="条码" />
        <ProFormText name="hazardClass" label="危险品类" />
        <ProFormDigit
          name="unitCost"
          label="单价"
          min={0}
          fieldProps={{ precision: 2, style: { width: '100%' } }}
        />
      </ModalForm>
    </>
  );
}