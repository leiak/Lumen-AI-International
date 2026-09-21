/**
 * Lumen Admin — ThemeToggle header button.
 *
 * Small UI control that toggles between light & dark mode.
 * - Reads the current mode from `useThemeMode()` (Task 4.1).
 * - Calls its `toggle` action on click — the hook handles localStorage
 *   persistence and the `<html>` class mirror.
 * - The `ConfigProvider` in App.tsx reacts to the mode change and re-renders
 *   all antd components with the matching token set, so no prop-drilling
 *   is needed.
 *
 * Place inside `<ConfigProvider>` (the BasicLayout satisfies this).
 */

import { Button, Tooltip } from 'antd';
import { BulbOutlined, BulbFilled } from '@ant-design/icons';

import { useThemeMode } from '@/theme/useThemeMode';

export function ThemeToggle() {
  const [mode, , toggle] = useThemeMode();
  const isDark = mode === 'dark';

  return (
    <Tooltip title={isDark ? '切换为浅色' : '切换为深色'}>
      <Button
        type="text"
        shape="circle"
        icon={isDark ? <BulbFilled /> : <BulbOutlined />}
        onClick={toggle}
        aria-label="toggle theme"
      />
    </Tooltip>
  );
}

export default ThemeToggle;
