import { useEffect } from 'react';
import { Routes, Route, useNavigate } from 'react-router-dom';
import { useKeycloak } from '@react-keycloak/web';
import { Navbar } from './components/Navbar';
import { Catalog } from './components/Catalog';
import { MyTrips } from './components/MyTrips';
import { Checkout } from './components/Checkout';
import './App.css';

export const App = () => {
  const { keycloak, initialized } = useKeycloak();
  const navigate = useNavigate();

  useEffect(() => {
    if (initialized && keycloak.authenticated) {
      const pendingRedirect = sessionStorage.getItem('post_login_redirect');
      if (pendingRedirect) {
        sessionStorage.removeItem('post_login_redirect');
        navigate(pendingRedirect);
      }
    }
  }, [initialized, keycloak.authenticated, navigate]);

  return (
    <div className="app-layout">
      <Navbar />
      <main className="app-main-content">
        <Routes>
          <Route path="/" element={<Catalog />} />
          <Route path="/catalog" element={<Catalog />} />
          <Route path="/my-trips" element={<MyTrips />} />
          <Route path="/checkout/:trainId" element={<Checkout />} />
        </Routes>
      </main>
    </div>
  );
};