import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { ReactKeycloakProvider } from '@react-keycloak/web';
import { Toaster } from 'react-hot-toast';
import keycloak from './keycloak';
import { Catalog } from './components/Catalog';
import { MyTrips } from './components/MyTrips';
import { Checkout } from './components/Checkout';
import { AppLayout } from './components/AppLayout';
import { NotificationProvider } from './components/NotificationProvider';

export default function App() {
  return (
    <ReactKeycloakProvider 
      authClient={keycloak}
      initOptions={{
        onLoad: 'check-sso',
        checkLoginIframe: false,
        silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html'
      }}
    >
      <NotificationProvider>
        <Toaster />
        <Router>
          <Routes>
            <Route path="/" element={<Catalog />} />
            <Route element={<AppLayout />}>
              <Route path="/catalog" element={<Catalog />} />
              <Route path="/my-trips" element={<MyTrips />} />
              <Route path="/checkout/:trainId" element={<Checkout />} />
            </Route>
          </Routes>
        </Router>
      </NotificationProvider>
    </ReactKeycloakProvider>
  );
}