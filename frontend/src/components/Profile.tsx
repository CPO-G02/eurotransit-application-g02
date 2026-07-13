import { useKeycloak } from '@react-keycloak/web';
import './Profile.css';

export const Profile = () => {
  const { keycloak } = useKeycloak();

  if (!keycloak?.authenticated) {
    return (
      <div className="profile-container">
        <div className="profile-card">
          <h2>Not Authenticated</h2>
          <button className="btn-signin" onClick={() => keycloak?.login()}>Sign In</button>
        </div>
      </div>
    );
  }

  const user = keycloak.tokenParsed;

  return (
    <div className="profile-container">
      <div className="profile-header">
        <h1>My Profile</h1>
        <p>Manage your account settings and preferences.</p>
      </div>

      <div className="profile-grid">
        <div className="profile-card">
          <h3>Personal Information</h3>
          <div className="info-row">
            <span className="label">Username</span>
            <span className="value">{user?.preferred_username}</span>
          </div>
          <div className="info-row">
            <span className="label">Full Name</span>
            <span className="value">{user?.given_name} {user?.family_name}</span>
          </div>
          <div className="info-row">
            <span className="label">Email</span>
            <span className="value">{user?.email}</span>
          </div>
        </div>

        <div className="profile-card">
          <h3>Account Security</h3>
          <p>You are currently logged in with your EuroTransit account.</p>
          <button className="btn-secondary-pro" onClick={() => keycloak.accountManagement()}>
            Manage Account Settings
          </button>
          <button className="btn-logout" onClick={() => keycloak.logout()}>
            Sign Out
          </button>
        </div>
      </div>
    </div>
  );
};