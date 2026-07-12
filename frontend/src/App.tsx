import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { ReactKeycloakProvider } from '@react-keycloak/web';
import keycloak from './keycloak';
import { Home } from './components/Home';
import { Catalog } from './components/Catalog';
import { AppLayout } from './components/AppLayout';

export default function App() {
  return (
    <ReactKeycloakProvider authClient={keycloak}>
      <Router>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route element={<AppLayout />}>
            <Route path="/catalog" element={<Catalog />} />
          </Route>
        </Routes>
      </Router>
    </ReactKeycloakProvider>
  );
}