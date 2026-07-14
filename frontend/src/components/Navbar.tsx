import { useNavigate, useLocation } from 'react-router-dom';
import { useKeycloak } from '@react-keycloak/web';
import './Navbar.css';

export const Navbar = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { keycloak } = useKeycloak();

  const currentPath = location.pathname;

  const getLinkClass = (path: string) => {
    if (currentPath === path) return 'nav-link active';
    if (path === '/catalog' && currentPath.startsWith('/checkout')) return 'nav-link active';
    return 'nav-link';
  };

  return (
    <header className="catalog-header">
      <div className="header-brand" onClick={() => navigate('/catalog')}>
        <span className="brand-accent-line"></span>
        <span className="brand-name">EuroTransit</span>
      </div>

      <div className="header-right">
        <nav className="header-nav">
          <button 
            className={getLinkClass('/catalog')} 
            onClick={() => navigate('/catalog')}
          >
            Catalog
          </button>
          <button 
            className={getLinkClass('/my-trips')} 
            onClick={() => navigate('/my-trips')}
          >
            My Trips
          </button>
        </nav>

        <div className="header-actions">
          {keycloak?.authenticated ? (
            <div className="user-profile">
              <span className="user-greeting">
                Welcome, <strong>{keycloak.tokenParsed?.preferred_username}</strong>
              </span>
              <button className="btn-logout" onClick={() => keycloak.logout()}>Logout</button>
            </div>
          ) : (
            <button className="btn-signin" onClick={() => keycloak?.login()}>Sign In</button>
          )}
        </div>
      </div>
    </header>
  );
};