/**
 * Lumen Admin — Notification Templates page.
 *
 * Lists templates from `/api/v1/notification/templates` (paged) and exposes
 * a "test send" modal that posts to `/notification/send` (see
 * NotificationTemplateController + NotificationSendController).
 *
 * Template create / edit / delete use the standard CRUD endpoints; the
 * channel select uses the literals the backend dispatcher understands
 * (EMAIL / SMS / IM).
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm, Result } from 'antd';
import { useRef, useState } from 'react';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type {
  NotificationChannel,
  NotificationTemplate,
  PageResult,
} from '@/types/api';

const CHANNEL_ENUM: Record<NotificationChannel, { text: string }> = {
  EMAIL: { text: '邮件' },
  SMS: { text: '短信' },
  IM: { text: '即时通讯' },
};

const STATUS_ENUM = {
  1: { text: '启用', status: 'Success' as const },
  0: { text: '禁用', status: 'Default' as const },
};

export default function Notification() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const access = useAccess();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<NotificationTemplate | null>(null);
  const [sendTpl, setSendTpl] = useState<NotificationTemplate | null>(null);

  const reload = () => actionRef.current?.reload();

  // Page-level gate: the list endpoint (`GET /notification/templates`) requires
  // `notification_template:list`. Without it, the table request 403s and the
  // user sees an empty page even when their per-row action buttons are
  // enabled. Short-circuit with a clear "no access" Result instead — this
  // matches the principle of "fail loud" and is consistent with the menu-level
  // gate that Task 2.2 will add at the BasicLayout layer.
  if (!access.canRead('notification_template:list')) {
    return (
      <Result
        status="403"
        title="无权访问"
        subTitle="当前账号缺少 notification_template:list 权限"
      />
    );
  }

  const handleDelete = async (id: number) => {
    await request.delete(`/notification/templates/${id}`);
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<NotificationTemplate>[] = [
    { title: '编码', dataIndex: 'code', width: 200 },
    {
      title: '渠道',
      dataIndex: 'channel',
      width: 100,
      valueEnum: CHANNEL_ENUM,
      valueType: 'select',
    },
    { title: '主题', dataIndex: 'subject', width: 220, ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueEnum: STATUS_ENUM,
      valueType: 'select',
    },
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
      width: 240,
      render: (_, record) => [
        <Button
          key="send"
          type="link"
          size="small"
          disabled={!access.canRead('notification_template:send')}
          onClick={() => setSendTpl(record)}
        >
          测试发送
        </Button>,
        <Button
          key="edit"
          type="link"
          size="small"
          disabled={!access.canRead('notification_template:update')}
          onClick={() => setEditing(record)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该模板?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('notification_template:delete')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<NotificationTemplate>
        headerTitle="通知模板"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('notification_template:create')}
            onClick={() => setCreateOpen(true)}
          >
            新建模板
          </Button>,
        ]}
        request={async (params) => {
          const res = (await request.get('/notification/templates', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
            },
          })) as unknown as PageResult<NotificationTemplate> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <ModalForm
        title="新建模板"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true }}
        onFinish={async (values) => {
          await request.post('/notification/templates', values);
          message.success('已创建');
          reload();
          return true;
        }}
      >
        <ProFormText
          name="code"
          label="编码"
          rules={[{ required: true, message: '请输入模板编码' }]}
        />
        <ProFormSelect
          name="channel"
          label="渠道"
          initialValue="EMAIL"
          valueEnum={CHANNEL_ENUM}
          rules={[{ required: true }]}
        />
        <ProFormText name="subject" label="主题" />
        <ProFormTextArea name="content" label="内容" fieldProps={{ rows: 4 }} />
        <ProFormSelect
          name="status"
          label="状态"
          initialValue={1}
          valueEnum={{ 1: '启用', 0: '禁用' }}
        />
      </ModalForm>

      <ModalForm
        title={`编辑模板 — ${editing?.code ?? ''}`}
        open={editing !== null}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={editing ?? undefined}
        onFinish={async (values) => {
          if (!editing) return false;
          await request.put(`/notification/templates/${editing.id}`, values);
          message.success('已更新');
          setEditing(null);
          reload();
          return true;
        }}
      >
        <ProFormSelect name="channel" label="渠道" valueEnum={CHANNEL_ENUM} />
        <ProFormText name="subject" label="主题" />
        <ProFormTextArea name="content" label="内容" fieldProps={{ rows: 4 }} />
        <ProFormSelect
          name="status"
          label="状态"
          valueEnum={{ 1: '启用', 0: '禁用' }}
        />
      </ModalForm>

      <ModalForm
        title={`测试发送 — ${sendTpl?.code ?? ''}`}
        open={sendTpl !== null}
        onOpenChange={(open) => {
          if (!open) setSendTpl(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={{ templateCode: sendTpl?.code ?? '' }}
        onFinish={async (values) => {
          await request.post('/notification/send', {
            templateCode: values.templateCode,
            receiver: values.receiver,
            vars: values.vars ?? {},
          });
          message.success('已提交发送');
          setSendTpl(null);
          return true;
        }}
      >
        <ProFormText
          name="templateCode"
          label="模板编码"
          rules={[{ required: true, message: '请输入模板编码' }]}
          disabled
        />
        <ProFormText
          name="receiver"
          label="接收方"
          rules={[{ required: true, message: '请输入接收方' }]}
          fieldProps={{ autoComplete: 'off' }}
        />
        <ProFormTextArea
          name="vars"
          label="变量 (JSON, 可选)"
          fieldProps={{ rows: 3, autoComplete: 'off' }}
        />
      </ModalForm>
    </>
  );
}
