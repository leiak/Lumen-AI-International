/**
 * Lumen Admin — Antd theme tokens for light & dark mode.
 *
 * - `lightTokens` keeps the Antd 5 default look with our brand colors
 *   sprinkled in for primary/info/success/warning/error.
 * - `darkTokens` flips on `theme.darkAlgorithm` (which derives surface,
 *   text and border shades) and re-applies the brand colors on top.
 *
 * `cssVar: true` makes Antd emit CSS variables (e.g. `--ant-color-primary`)
 * so non-Antd CSS can react to theme changes; `hashed: false` keeps class
 * names readable in dev tools. `wireframe: false` opts into Antd 5's
 * redesigned components instead of the legacy "wireframe" look.
 *
 * The Menu/header/sider overrides are tuned for BasicLayout (ProLayout),
 * whose sider menu sits over a dark header strip in both modes.
 */

import type { ThemeConfig } from 'antd';
import { theme } from 'antd';

/**
 * Brand color palette. Override individual roles below as needed.
 * Keep this small — Antd 5's algorithm generates derived shades automatically.
 */
const brand = {
  light: {
    colorPrimary: '#1677ff', // Antd default blue
    colorInfo: '#1677ff',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
  },
  dark: {
    colorPrimary: '#1668dc',
    colorInfo: '#1668dc',
    colorSuccess: '#49aa19',
    colorWarning: '#d89614',
    colorError: '#dc4446',
  },
};

export const lightTokens: ThemeConfig = {
  cssVar: true,
  hashed: false,
  token: {
    ...brand.light,
    borderRadius: 6,
    fontSize: 14,
  },
  components: {
    Layout: {
      headerBg: '#ffffff',
      siderBg: '#001529',
      bodyBg: '#f5f5f5',
    },
    Menu: {
      darkItemBg: '#001529',
    },
  },
};

export const darkTokens: ThemeConfig = {
  cssVar: true,
  hashed: false,
  algorithm: theme.darkAlgorithm,
  token: {
    ...brand.dark,
    borderRadius: 6,
    fontSize: 14,
  },
  components: {
    Layout: {
      headerBg: '#141414',
      siderBg: '#000000',
      bodyBg: '#000000',
    },
    Menu: {
      darkItemBg: '#000000',
    },
  },
};