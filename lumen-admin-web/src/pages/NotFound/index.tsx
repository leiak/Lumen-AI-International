/**
 * Lumen Admin — 404 NotFound page.
 *
 * Renders Antd's `Result` component in 404 mode with a single "回到首页"
 * CTA that bounces the user to "/". Used by App.tsx for both the catch-all
 * `*` route and the explicit `/403` route (denied access — a 404 visually
 * is friendlier than a hard 403 and is consistent with the placeholder
 * auth fallback until a dedicated 403 page is introduced).
 */

import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';

export default function NotFound() {
  const nav = useNavigate();
  return (
    <Result
      status="404"
      title="404"
      subTitle="抱歉，您访问的页面不存在。"
      extra={
        <Button type="primary" onClick={() => nav('/')}>
          回到首页
        </Button>
      }
    />
  );
}
