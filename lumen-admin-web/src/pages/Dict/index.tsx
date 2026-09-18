/**
 * Lumen Admin — Data Dictionary page.
 *
 * ProTable lists `/api/v1/dicts` (paged). ModalForm creates / edits dicts;
 * per-row delete calls `DELETE /dicts/{id}` (see SysDictController.java).
 * Dictionary items themselves live behind `/dicts/{id}/items` and will get
 * their own sub-page in phase 4.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormText,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { getAccess, type AccessState } from '@/access';
import { request } from '@/services/request';
import type { PageResult, SysDict } from '@/types/api';

export default function Dict() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const [access, setAccess] = useState<AccessState>({ canRead: () => false, perms: [] });
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<SysDict | null>(null);

  useEffect(() => {
    getAccess().then(setAccess);
  }, []);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/dicts/${id}`);
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<SysDict>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '编码', dataIndex: 'code', width: 220 },
    { title: '名称', dataIndex: 'name', width: 200 },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      valueType: 'dateTime',
      width: 180,
      hideInSearch: true,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 180,
      render: (_, record) => [
        <Button
          key="edit"
          type="link"
          size="small"
          disabled={!access.canRead('dict:update')}
          onClick={() => setEditing(record)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该字典?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('dict:delete')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<SysDict>
        headerTitle="数据字典"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('dict:create')}
            onClick={() => setCreateOpen(true)}
          >
            新建字典
          </Button>,
        ]}
        request={async (params) => {
          const res = (await request.get('/dicts', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
            },
          })) as unknown as PageResult<SysDict> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <ModalForm
        title="新建字典"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true }}
        onFinish={async (values) => {
          await request.post('/dicts', values);
          message.success('已创建');
          reload();
          return true;
        }}
      >
        <ProFormText
          name="code"
          label="编码"
          rules={[{ required: true, message: '请输入字典编码' }]}
        />
        <ProFormText name="name" label="名称" rules={[{ required: true, message: '请输入字典名称' }]} />
        <ProFormTextArea name="description" label="描述" />
      </ModalForm>

      <ModalForm
        title={`编辑字典 — ${editing?.name ?? ''}`}
        open={editing !== null}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={editing ?? undefined}
        onFinish={async (values) => {
          if (!editing) return false;
          await request.put(`/dicts/${editing.id}`, values);
          message.success('已更新');
          setEditing(null);
          reload();
          return true;
        }}
      >
        <ProFormText name="name" label="名称" rules={[{ required: true, message: '请输入字典名称' }]} />
        <ProFormTextArea name="description" label="描述" />
      </ModalForm>
    </>
  );
}
