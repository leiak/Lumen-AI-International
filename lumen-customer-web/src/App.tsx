import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './store/authStore';
import PortalLayout from './layouts/PortalLayout';
import PublicLayout from './layouts/PublicLayout';
import Login from './pages/Login';
import SelectCustomer from './pages/SelectCustomer';
import TrackList from './pages/Track/TrackList';
import TrackDetail from './pages/Track/TrackDetail';
import Profile from './pages/Profile';
import NotFound from './pages/NotFound';

export default function App() {
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route path="/login" element={<Login />} />
      </Route>
      <Route element={<PortalLayout />}>
        <Route path="/select-customer" element={<RequireAuth><SelectCustomer /></RequireAuth>} />
        <Route path="/track" element={<RequireAuth><TrackList /></RequireAuth>} />
        <Route path="/track/:doId" element={<RequireAuth><TrackDetail /></RequireAuth>} />
        <Route path="/profile" element={<RequireAuth><Profile /></RequireAuth>} />
        <Route path="/" element={<Navigate to="/track" />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = useAuthStore(s => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}