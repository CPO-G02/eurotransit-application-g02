import { useKeycloak } from '@react-keycloak/web';
import { useNavigate } from 'react-router-dom';
import './Home.css';

export const Home = () => {
  const { keycloak, initialized } = useKeycloak();
  const navigate = useNavigate();

  return (
    <div className="login-viewport">
      <div className="train-track-bg">
        <svg viewBox="0 0 1200 200" className="static-train-svg" xmlns="http://www.w3.org/2000/svg">
          <line x1="0" y1="160" x2="1200" y2="160" stroke="#30363d" strokeWidth="4" />
          <path d="M 0,60 L 850,60 C 1020,60 1130,100 1180,150 L 0,150 Z" fill="#161b22" stroke="#30363d" strokeWidth="2" />
          <path d="M 0,85 L 830,85 C 950,85 1040,105 1100,130 L 0,130 Z" fill="#0d1117" />
          <line x1="150" y1="85" x2="150" y2="130" stroke="#161b22" strokeWidth="8" />
          <line x1="300" y1="85" x2="300" y2="130" stroke="#161b22" strokeWidth="8" />
          <line x1="450" y1="85" x2="450" y2="130" stroke="#161b22" strokeWidth="8" />
          <line x1="600" y1="85" x2="600" y2="130" stroke="#161b22" strokeWidth="8" />
          <line x1="750" y1="85" x2="750" y2="130" stroke="#161b22" strokeWidth="8" />
          <line x1="900" y1="90" x2="900" y2="130" stroke="#161b22" strokeWidth="8" />
          <path d="M 0,136 L 1110,136 C 1130,136 1150,142 1165,150 L 0,150 Z" fill="#ea580c" />
        </svg>
      </div>

      <div className="login-card-pro">
        <div className="brand-header">
          <span className="brand-tag">EUROPEAN HIGH-SPEED RAILWAY</span>
          <h1 className="brand-title">EuroTransit</h1>
          <p className="brand-subtitle">The new standard of modern European travel.</p>
        </div>

        <div className="action-group">
          {initialized && !keycloak.authenticated ? (
            <button className="btn-primary-pro" onClick={() => keycloak.login()}>
              Sign In with Keycloak
            </button>
          ) : (
            <button className="btn-primary-pro" onClick={() => navigate('/catalog')}>
              Log In
            </button>
          )}
          
          <button className="btn-secondary-pro" onClick={() => navigate('/catalog')}>
            Continue as Guest
          </button>
        </div>

        <div className="card-footer">
          <span>Secured by OpenID Connect</span>
        </div>
      </div>
    </div>
  );
};