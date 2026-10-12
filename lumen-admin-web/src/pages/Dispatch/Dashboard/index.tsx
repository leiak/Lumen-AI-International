/**
 * Lumen Admin — Vehicle Utilization Dashboard (Iteration 4.1 PR5).
 *
 * Top KPI cards (4): total dispatched, average utilization, total weight, total volume.
 * Date range picker (default: last 7 days, max: 90 days).
 * ProTable below: per-(date, vehicle) breakdown with utilization rendered as Progress bar.
 *
 * Perm gate: `dispatch:dashboard:view` (V21 seed).
 */

import type { ProColumns } from '@ant-design/pro-components';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { App as AntApp, Card, Col, DatePicker, Progress, Row, Statistic, Tag } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useEffect, useState } from 'react';

import { useAccess } from '@/hooks/useAccess';
import { utilizationService, type DailyUtilizationRow, type FleetUtilizationResponse } from '@/services/dispatch/utilization';

const { RangePicker } = DatePicker;

function toIso(d: Dayjs): string {
  return d.format('YYYY-MM-DD');
}

function defaultRange(): [Dayjs, Dayjs] {
  // last 7 days inclusive (today is the last day)
  return [dayjs().subtract(6, 'day').startOf('day'), dayjs().endOf('day')];
}

export default function DispatchDashboard() {
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();
  const [range, setRange] = useState<[Dayjs, Dayjs]>(defaultRange());
  const [fleet, setFleet] = useState<FleetUtilizationResponse | null>(null);
  const [rows, setRows] = useState<DailyUtilizationRow[]>([]);
  const [loading, setLoading] = useState(false);

  const reload = async (nextRange: [Dayjs, Dayjs]) => {
    setLoading(true);
    try {
      const dateStart = toIso(nextRange[0]);
      const dateEnd = toIso(nextRange[1]);
      const [fleetData, dailyRows] = await Promise.all([
        utilizationService.fleet({ dateStart, dateEnd }),
        utilizationService.daily({ dateStart, dateEnd }),
      ]);
      setFleet(fleetData);
      setRows(dailyRows);
    } catch (err) {
      antMessage.error((err as Error).message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (access.canRead('dispatch:dashboard:view')) {
      void reload(range);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!access.canRead('dispatch:dashboard:view')) {
    return (
      <Card>
        <Tag color="error">无权访问</Tag>
        <span style={{ marginLeft: 8 }}>需要 dispatch:dashboard:view 权限</span>
      </Card>
    );
  }

  const totalDispatched = fleet?.totalDispatched ?? 0;
  const avgRate = fleet?.averageUtilizationRate ?? 0;
  const totalWeight = fleet?.totalWeightKg ?? 0;
  const totalVolume = fleet?.totalVolumeM3 ?? 0;

  const columns: ProColumns<DailyUtilizationRow>[] = [
    {
      title: '日期',
      dataIndex: 'date',
      width: 120,
      valueType: 'date',
    },
    {
      title: '车牌号',
      dataIndex: 'plateNo',
      width: 140,
    },
    {
      title: '派送单数',
      dataIndex: 'dispatchedCount',
      width: 100,
      render: (_, r) => <span>{r.dispatchedCount}</span>,
    },
    {
      title: '总重 (kg)',
      dataIndex: 'totalWeightKg',
      width: 120,
      render: (_, r) => r.totalWeightKg.toFixed(2),
    },
    {
      title: '体积 (m³)',
      dataIndex: 'totalVolumeM3',
      width: 120,
      render: (_, r) => r.totalVolumeM3.toFixed(2),
    },
    {
      title: '利用率',
      dataIndex: 'utilizationRate',
      width: 200,
      render: (_, r) => {
        const pct = Math.round(r.utilizationRate * 100);
        const color = pct >= 80 ? 'green' : pct >= 50 ? 'gold' : 'red';
        return <Progress percent={pct} size="small" status={pct >= 80 ? 'success' : 'normal'} strokeColor={color} />;
      },
    },
  ];

  return (
    <ProCard split="vertical">
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={5}>
          <Card>
            <Statistic title="总派送单数" value={totalDispatched} suffix="单" />
          </Card>
        </Col>
        <Col span={5}>
          <Card>
            <Statistic
              title="平均利用率"
              value={(avgRate * 100).toFixed(1)}
              suffix="%"
              valueStyle={{ color: avgRate >= 0.8 ? '#3f8600' : avgRate >= 0.5 ? '#d48806' : '#cf1322' }}
            />
          </Card>
        </Col>
        <Col span={7}>
          <Card>
            <Statistic title="总运输重量" value={totalWeight.toFixed(2)} suffix="kg" />
          </Card>
        </Col>
        <Col span={7}>
          <Card>
            <Statistic title="总体积" value={totalVolume.toFixed(2)} suffix="m³" />
          </Card>
        </Col>
      </Row>

      <ProCard
        title="车辆利用率明细"
        extra={
          <RangePicker
            value={range}
            onChange={(v) => {
              if (v && v[0] && v[1]) {
                const next: [Dayjs, Dayjs] = [v[0], v[1]];
                setRange(next);
                void reload(next);
              }
            }}
            disabledDate={(d) => d && d.isAfter(dayjs(), 'day')}
            allowClear={false}
          />
        }
        loading={loading}
      >
        <ProTable<DailyUtilizationRow>
          rowKey={(r) => `${r.date}-${r.vehicleId}`}
          columns={columns}
          dataSource={rows}
          search={false}
          options={false}
          pagination={{ pageSize: 20, showSizeChanger: true }}
        />
      </ProCard>
    </ProCard>
  );
}
