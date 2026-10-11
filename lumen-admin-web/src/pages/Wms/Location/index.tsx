/**
 * Lumen Admin — WMS Location list (PR1 vertical slice, Iteration 3.0a).
 *
 * ProTable lists `/api/v1/wms/locations` (paged). ModalForm creates /
 * edits locations; per-row delete calls `DELETE /wms/locations/{id}`.
 *
 * Locations are scoped to a warehouse via the `warehouseId` FK; the create
 * form requires picking a parent warehouse (loaded from
 * `/wms/warehouses?pageSize=1000` so the dropdown is complete for the
 * small masterdata set). Status column reflects WmsLocationStatus
 * (ACTIVE / FROZEN / DISABLED).
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormDigit,
  ProFormSelect,
  ProFormText,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult, WmsLocation, WmsWarehouse } from '@/types/api';

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '可用' },
  { value: 'FROZEN', label: '已冻结' },
  { value: 'DISABLED', label: '已停用' },
];

interface LocationFormValues {
  warehouseId: number;
  code: string;
  zone?: string;
  capacity?: number;
  status?: string;
}

export default function WmsLocationPage() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const access = useAccess();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<WmsLocation | null>(null);

  // Cached parent-warehouse options for the create/edit form. Pulled from
  // `/wms/warehouses?pageSize=1000` so the Select is exhaustive for the
  // realistically-small WMS warehouse count.
  const [warehouseOptions, setWarehouseOptions] = useState<
    Array<{ value: number; label: string }>
  >([]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = (await request.get('/wms/warehouses', {
          params: { pageNum: 1, pageSize: 1000 },
        })) as unknown as PageResult<WmsWarehouse> | null;
        if (cancelled) return;
        setWarehouseOptions(
          (res?.records ?? []).map((w) => ({
            value: w.id,
            label: `${w.code} — ${w.name}`,
          })),
        );
      } catch {
        // axios interceptor surfaces the toast; leave options empty.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/wms/locations/${id}`);
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<WmsLocation>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '仓库ID', dataIndex: 'warehouseId', width: 100 },
    { title: '编码', dataIndex: 'code', width: 160 },
    { title: '区域', dataIndex: 'zone', width: 120, hideInSearch: true },
    {
      title: '容量',
      dataIndex: 'capacity',
      width: 100,
      hideInSearch: true,
      render: (_, r) => (r.capacity != null ? r.capacity : '—'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      valueEnum: {
        ACTIVE: { text: '可用', status: 'Success' },
        FROZEN: { text: '已冻结', status: 'Warning' },
        DISABLED: { text: '已停用', status: 'Default' },
      },
      render: (_, record) => <StatusTag value={record.status ?? ''} />,
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
          disabled={!access.canRead('wms:location:update')}
          onClick={() => setEditing(record)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该库位?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('wms:location:delete')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<WmsLocation>
        headerTitle="库位管理"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('wms:location:create')}
            onClick={() => setCreateOpen(true)}
          >
            新建库位
          </Button>,
        ]}
        request={async (params) => {
          const res = (await request.get('/wms/locations', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              warehouseId: params.warehouseId,
              keyword: params.keyword,
              status: params.status,
            },
          })) as unknown as PageResult<WmsLocation> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <ModalForm<LocationFormValues>
        title="新建库位"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true }}
        onFinish={async (values) => {
          await request.post('/wms/locations', values);
          message.success('已创建');
          reload();
          return true;
        }}
      >
        <ProFormSelect
          name="warehouseId"
          label="所属仓库"
          rules={[{ required: true, message: '请选择所属仓库' }]}
          options={warehouseOptions}
          showSearch
        />
        <ProFormText
          name="code"
          label="编码"
          rules={[{ required: true, message: '请输入库位编码' }]}
        />
        <ProFormText name="zone" label="区域" placeholder="例如: A / B / 收货区" />
        <ProFormDigit name="capacity" label="容量" min={0} fieldProps={{ style: { width: '100%' } }} />
        <ProFormSelect
          name="status"
          label="状态"
          initialValue="ACTIVE"
          options={STATUS_OPTIONS}
        />
      </ModalForm>

      <ModalForm<LocationFormValues>
        title={`编辑库位 — ${editing?.code ?? ''}`}
        open={editing !== null}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={editing ?? undefined}
        onFinish={async (values) => {
          if (!editing) return false;
          await request.put(`/wms/locations/${editing.id}`, values);
          message.success('已更新');
          setEditing(null);
          reload();
          return true;
        }}
      >
        <ProFormSelect
          name="warehouseId"
          label="所属仓库"
          rules={[{ required: true, message: '请选择所属仓库' }]}
          options={warehouseOptions}
          showSearch
        />
        <ProFormText name="code" label="编码" />
        <ProFormText name="zone" label="区域" />
        <ProFormDigit name="capacity" label="容量" min={0} fieldProps={{ style: { width: '100%' } }} />
        <ProFormSelect name="status" label="状态" options={STATUS_OPTIONS} />
      </ModalForm>
    </>
  );
}