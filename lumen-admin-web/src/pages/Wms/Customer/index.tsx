/**
 * Lumen Admin — WMS Customer list (PR1 vertical slice, Iteration 3.0a).
 *
 * ProTable lists `/api/v1/wms/customers` (paged). ModalForm creates / edits
 * customers; per-row delete calls `DELETE /wms/customers/{id}`.
 *
 * Pure masterdata — `code`, `name`, `contact`, `phone`, `address`. No FK
 * column, no status field on the WmsCustomer entity.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormText,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm } from 'antd';
import { useRef, useState } from 'react';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult, WmsCustomer } from '@/types/api';

interface CustomerFormValues {
  code: string;
  name: string;
  contact?: string;
  phone?: string;
  address?: string;
}

export default function WmsCustomerPage() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const access = useAccess();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<WmsCustomer | null>(null);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/wms/customers/${id}`);
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<WmsCustomer>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '编码', dataIndex: 'code', width: 160 },
    { title: '名称', dataIndex: 'name', width: 200 },
    { title: '联系人', dataIndex: 'contact', width: 140, hideInSearch: true },
    { title: '电话', dataIndex: 'phone', width: 160, hideInSearch: true },
    { title: '地址', dataIndex: 'address', ellipsis: true, hideInSearch: true },
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
          disabled={!access.canRead('wms:customer:update')}
          onClick={() => setEditing(record)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该客户?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('wms:customer:delete')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<WmsCustomer>
        headerTitle="客户管理"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('wms:customer:create')}
            onClick={() => setCreateOpen(true)}
          >
            新建客户
          </Button>,
        ]}
        request={async (params) => {
          const res = (await request.get('/wms/customers', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
            },
          })) as unknown as PageResult<WmsCustomer> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <ModalForm<CustomerFormValues>
        title="新建客户"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true }}
        onFinish={async (values) => {
          await request.post('/wms/customers', values);
          message.success('已创建');
          reload();
          return true;
        }}
      >
        <ProFormText
          name="code"
          label="编码"
          rules={[{ required: true, message: '请输入客户编码' }]}
        />
        <ProFormText
          name="name"
          label="名称"
          rules={[{ required: true, message: '请输入客户名称' }]}
        />
        <ProFormText name="contact" label="联系人" />
        <ProFormText name="phone" label="电话" />
        <ProFormTextArea name="address" label="地址" />
      </ModalForm>

      <ModalForm<CustomerFormValues>
        title={`编辑客户 — ${editing?.name ?? ''}`}
        open={editing !== null}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={editing ?? undefined}
        onFinish={async (values) => {
          if (!editing) return false;
          await request.put(`/wms/customers/${editing.id}`, values);
          message.success('已更新');
          setEditing(null);
          reload();
          return true;
        }}
      >
        <ProFormText name="code" label="编码" />
        <ProFormText name="name" label="名称" />
        <ProFormText name="contact" label="联系人" />
        <ProFormText name="phone" label="电话" />
        <ProFormTextArea name="address" label="地址" />
      </ModalForm>
    </>
  );
}