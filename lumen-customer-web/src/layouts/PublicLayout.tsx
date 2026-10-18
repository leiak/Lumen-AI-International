/**
 * 4.5A.4-T4.3 — PublicLayout (bare wrapper for /login).
 *
 * Renders an <Outlet/> centered in the viewport; no auth state required.
 */

import React from 'react';
import { Outlet } from 'react-router-dom';
import './PublicLayout.css';

const PublicLayout: React.FC = () => {
  return (
    <div className="public-layout">
      <div className="public-layout__card">
        <Outlet />
      </div>
    </div>
  );
};

export default PublicLayout;
