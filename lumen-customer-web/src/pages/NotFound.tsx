/**
 * 4.5A.4-T4.3 — NotFound (404) page.
 *
 * Lives inside the PortalLayout's outlet so we can show the standard chrome
 * (user is signed in, just clicked a stale link) but the layout itself swaps
 * to the bare public layout when no token is present. We render a Result with a
 * back-to-home button.
 */

import React from 'react';
import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { zhCN } from '../locale/zh-CN';

const NotFound: React.FC = () => {
  const navigate = useNavigate();
  return (
    <Result
      status="404"
      title={zhCN.notFound.title}
      subTitle={zhCN.notFound.description}
      extra={
        <Button type="primary" onClick={() => navigate('/track')}>
          {zhCN.notFound.back}
        </Button>
      }
    />
  );
};

export default NotFound;
