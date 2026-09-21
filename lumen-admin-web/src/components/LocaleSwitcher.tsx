/**
 * Lumen Admin — LocaleSwitcher header control.
 *
 * Dropdown that lets the user switch between the supported locales.
 * Reads/writes via the `useLocale()` hook (Task 4.3).
 *
 * Place anywhere inside <LocaleProvider> — BasicLayout's header is the
 * canonical spot, sitting next to ThemeToggle.
 */
import { Button, Dropdown, type MenuProps } from 'antd';
import { GlobalOutlined } from '@ant-design/icons';
import { useLocale, type Locale } from '@/locale';

const LOCALE_LABELS: Record<Locale, string> = {
  'zh-CN': '中文',
  'en-US': 'English',
};

export function LocaleSwitcher() {
  const { locale, setLocale } = useLocale();

  const items: MenuProps['items'] = (Object.keys(LOCALE_LABELS) as Locale[]).map((key) => ({
    key,
    label: LOCALE_LABELS[key],
    disabled: key === locale,
  }));

  return (
    <Dropdown
      menu={{
        items,
        onClick: ({ key }) => setLocale(key as Locale),
        selectedKeys: [locale],
      }}
      placement="bottomRight"
    >
      <Button
        type="text"
        shape="circle"
        icon={<GlobalOutlined />}
        aria-label="switch language"
      />
    </Dropdown>
  );
}

export default LocaleSwitcher;
