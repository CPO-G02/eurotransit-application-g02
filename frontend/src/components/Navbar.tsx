import { useNavigate } from 'react-router-dom';
import { useKeycloak } from '@react-keycloak/web';
import './Navbar.css';

export const Navbar = () => {
  const navigate = useNavigate();
  const { keycloak } = useKeycloak();

  return (
    <header className="catalog-header">
      <div className="header-brand" onClick={() => navigate('/catalog')}>
        <span className="brand-icon">🚄</span>
        <span className="brand-name">EuroTransit</span>
      </div>

      <div className="header-actions">
        {keycloak?.authenticated ? (
          <div className="user-profile">
            <span className="user-greeting">Welcome, <strong>{keycloak.tokenParsed?.preferred_username}</strong></span>
            <button className="btn-logout" onClick={() => keycloak.logout()}>Logout</button>
          </div>
        ) : (
          <button className="btn-signin" onClick={() => keycloak?.login()}>Sign In</button>
        )}
      </div>
    </header>
  );
};