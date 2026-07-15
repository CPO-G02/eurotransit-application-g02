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
    <header className="site-header">
      <button className="header-brand" onClick={() => navigate('/')}>
        <span className="brand-mark" aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <rect x="4" y="3" width="16" height="14" rx="4" stroke="currentColor" strokeWidth="1.6" />
            <path d="M4 11h16" stroke="currentColor" strokeWidth="1.6" />
            <circle cx="8.5" cy="14" r="1" fill="currentColor" />
            <circle cx="15.5" cy="14" r="1" fill="currentColor" />
            <path d="M7 20l1.5-3M17 20l-1.5-3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
        </span>
        <span className="brand-name">
          <span className="brand-name-a">EURO</span><span className="brand-name-b">TRANSIT</span>
        </span>
      </button>

      <div className="header-right">
        <nav className="header-nav">
          <button
            className={getLinkClass('/catalog')}
            onClick={() => navigate('/catalog')}
          >
            Trains
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
                <span className="session-dot" aria-hidden="true" />
                <span>Welcome, <strong>{keycloak.tokenParsed?.preferred_username}</strong></span>
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