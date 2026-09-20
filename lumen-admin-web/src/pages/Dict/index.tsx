/**
 * Lumen Admin — Data Dictionary page.
 *
 * ProTable lists `/api/v1/dicts` (paged). ModalForm creates / edits dicts;
 * per-row delete calls `DELETE /dicts/{id}` (see SysDictController.java).
 *
 * Clicking a row's "字典项" button opens a right-side Drawer that loads the
 * dict's items via `GET /dicts/{code}/items` (returns a flat entity list,
 * not paged — the Drawer sub-table shows them all at once because real-world
 * dicts are small). Inside the Drawer the user can add / edit / delete
 * items via the `/dict-items` CRUD endpoints.
 *
 * Perm gates use the existing `dict:*` codes — the seed migrations do not
 * define `dict_item:*` and the SysDictItemController reuses `dict:list/view/
 * create/update/delete`. This matches the seed (V6__fix_permissions.sql).
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormText,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { PlusOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
} from 'antd';
import { useRef, useState } from 'react';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult, SysDict, SysDictItem } from '@/types/api';

/** Form shape for the add / edit item Modal. */
interface ItemFormValues {
  code: string;
  label: string;
  sortOrder?: number;
  status: 0 | 1;
}

const STATUS_OPTIONS: Array<{ value: 0 | 1; label: string }> = [
  { value: 1, label: '启用' },
  { value: 0, label: '禁用' },
];

export default function Dict() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const access = useAccess();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<SysDict | null>(null);

  // ---- Drawer (dict items sub-table) ----
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [currentDict, setCurrentDict] = useState<SysDict | null>(null);
  const drawerActionRef = useRef<ActionType>();
  /**
   * Bump to force the Drawer's ProTable to refetch after a successful
   * create / update / delete. ProTable doesn't expose a reload trigger when
   * the `request` callback closes over other reactive state, so we lean on a
   * counter key in the dependency list of the fetch closure.
   */
  const [itemsReloadKey, bumpItemsReload] = useState(0);

  // ---- Add / Edit Modal (inside Drawer) ----
  const [editOpen, setEditOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<SysDictItem | null>(null);
  const [itemForm] = Form.useForm<ItemFormValues>();

  const reload = () => actionRef.current?.reload();
  const reloadItems = () => drawerActionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/dicts/${id}`);
    message.success('已删除');
    reload();
  };

  const openDictItems = (dict: SysDict) => {
    setCurrentDict(dict);
    setDrawerOpen(true);
    // Stale actionRef from a previous Drawer may linger if the user closes
    // and reopens; the next reload() call is safe because ProTable no-ops
    // when no request is in flight.
  };

  const openAddItem = () => {
    setEditingItem(null);
    itemForm.resetFields();
    itemForm.setFieldsValue({ status: 1, sortOrder: 0 });
    setEditOpen(true);
  };

  const openEditItem = (item: SysDictItem) => {
    setEditingItem(item);
    itemForm.setFieldsValue({
      code: item.code,
      label: item.label,
      sortOrder: item.sortOrder ?? 0,
      status: item.status,
    });
    setEditOpen(true);
  };

  const saveItem = async () => {
    const values = await itemForm.validateFields();
    if (!currentDict) return;
    try {
      if (editingItem) {
        await request.put(`/dict-items/${editingItem.id}`, values);
        message.success('字典项已更新');
      } else {
        // Backend's POST /dict-items accepts a SysDictItem entity. Send
        // dictId explicitly so the item is scoped to the right dict even if
        // the server doesn't auto-derive it from the path.
        await request.post(`/dict-items`, {
          ...values,
          dictId: currentDict.id,
        });
        message.success('字典项已创建');
      }
      setEditOpen(false);
      setEditingItem(null);
      bumpItemsReload((n) => n + 1);
      reloadItems();
    } catch {
      // axios interceptor already surfaces the failure notification.
    }
  };

  const deleteItem = async (id: number) => {
    await request.delete(`/dict-items/${id}`);
    message.success('字典项已删除');
    bumpItemsReload((n) => n + 1);
    reloadItems();
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
      width: 240,
      render: (_, record) => [
        <Button
          key="items"
          type="link"
          size="small"
          disabled={!access.canRead('dict:list')}
          onClick={() => openDictItems(record)}
        >
          字典项
        </Button>,
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

      {/* --------------------------------------------------------------- */}
      {/* Dict-items Drawer + sub-table                                    */}
      {/* --------------------------------------------------------------- */}
      <Drawer
        title={
          currentDict
            ? `${currentDict.name} (${currentDict.code}) — 字典项管理`
            : '字典项管理'
        }
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false);
          setCurrentDict(null);
          setEditingItem(null);
        }}
        width={900}
        destroyOnClose
      >
        <ProTable<SysDictItem>
          key={itemsReloadKey}
          headerTitle="字典项"
          actionRef={drawerActionRef}
          rowKey="id"
          search={false}
          pagination={{ pageSize: 10, showSizeChanger: true }}
          toolBarRender={() => [
            <Button
              key="add"
              type="primary"
              icon={<PlusOutlined />}
              disabled={!access.canRead('dict:create')}
              onClick={openAddItem}
            >
              新增字典项
            </Button>,
          ]}
          request={async () => {
            if (!currentDict) return { data: [], total: 0, success: true };
            const items = (await request.get(
              `/dicts/${encodeURIComponent(currentDict.code)}/items`,
            )) as unknown as SysDictItem[] | null;
            return {
              data: items ?? [],
              total: items?.length ?? 0,
              success: true,
            };
          }}
          columns={[
            {
              title: '编码',
              dataIndex: 'code',
              width: 160,
            },
            {
              title: '文本',
              dataIndex: 'label',
              width: 180,
              ellipsis: true,
            },
            {
              title: '排序',
              dataIndex: 'sortOrder',
              width: 80,
              hideInSearch: true,
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (_, record) => <StatusTag value={record.status} />,
            },
            {
              title: '更新时间',
              dataIndex: 'updatedAt',
              width: 170,
              render: (_, record) => <DateTimeColumn value={record.updatedAt} />,
            },
            {
              title: '操作',
              valueType: 'option',
              width: 160,
              render: (_, record) => [
                <Button
                  key="edit"
                  type="link"
                  size="small"
                  disabled={!access.canRead('dict:update')}
                  onClick={() => openEditItem(record)}
                >
                  编辑
                </Button>,
                <Popconfirm
                  key="del"
                  title="确认删除该字典项?"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={() => deleteItem(record.id)}
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
          ]}
        />
      </Drawer>

      {/* --------------------------------------------------------------- */}
      {/* Add / Edit item Modal                                            */}
      {/* --------------------------------------------------------------- */}
      <Modal
        title={editingItem ? '编辑字典项' : '新增字典项'}
        open={editOpen}
        onCancel={() => {
          setEditOpen(false);
          setEditingItem(null);
        }}
        onOk={saveItem}
        destroyOnClose
        maskClosable={false}
        confirmLoading={false}
      >
        <Form<ItemFormValues> form={itemForm} layout="vertical" preserve={false}>
          <Form.Item
            name="code"
            label="字典项编码"
            rules={[{ required: true, message: '请输入字典项编码' }]}
          >
            {/* Disallow renaming an existing item: the (tenant_id, dict_id, code)
                unique index would reject the update, and renaming a dict item
                would silently break any caller referencing it by code. */}
            <Input disabled={!!editingItem} placeholder="例如: MALE" />
          </Form.Item>
          <Form.Item
            name="label"
            label="字典项文本"
            rules={[{ required: true, message: '请输入字典项文本' }]}
          >
            <Input placeholder="例如: 男" />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber min={0} style={{ width: '100%' }} placeholder="数字越小越靠前" />
          </Form.Item>
          <Form.Item
            name="status"
            label="状态"
            rules={[{ required: true, message: '请选择状态' }]}
          >
            <Select options={STATUS_OPTIONS} placeholder="请选择" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
