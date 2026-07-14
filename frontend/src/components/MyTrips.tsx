import { useEffect, useState } from 'react';
import { useKeycloak } from '@react-keycloak/web';
import { useNavigate } from 'react-router-dom';
import './MyTrips.css';

interface Trip {
  ticket_id: string;
  train_id: string;
  origin: string;
  destination: string;
  departure: string;
  selected_class: string;
  price: number;
  currency: string;
  status: 'upcoming' | 'completed';
}

export const MyTrips = () => {
  const { keycloak, initialized } = useKeycloak();
  const navigate = useNavigate();
  const [trips, setTrips] = useState<Trip[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchTrips = async () => {
      if (!initialized || !keycloak.authenticated) {
        setLoading(false);
        return;
      }

      try {
        setLoading(true);
        const response = await fetch('https://g02.cpo2026.it/api/v1/orders', {
          headers: {
            'Authorization': `Bearer ${keycloak.token}`,
            'Content-Type': 'application/json'
          }
        });

        if (!response.ok) throw new Error('Error fetching trips');
        const data: Trip[] = await response.json();
        setTrips(data);
      } catch (err) {
        setError('Could not load your trips.');
      } finally {
        setLoading(false);
      }
    };

    fetchTrips();
  }, [initialized, keycloak.authenticated, keycloak.token]);

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString('ca-ES', { weekday: 'short', day: 'numeric', month: 'short' });
  };

  const formatTime = (dateString: string) => {
    return new Date(dateString).toLocaleTimeString('ca-ES', { hour: '2-digit', minute: '2-digit' });
  };

  if (!initialized || loading) return <div className="trips-loading">Loading dashboard...</div>;

  return (
    <div className="trips-container">
      <div className="trips-header">
        <h1 className="trips-title">My Trips</h1>
        <p className="trips-subtitle">Welcome back, {keycloak.tokenParsed?.preferred_username}</p>
      </div>

      {error && (
        <div className="trips-error-banner" style={{ marginBottom: '2rem', color: '#ff4d4d', background: '#2d1a1a', padding: '1rem', borderRadius: '8px', border: '1px solid #ff4d4d' }}>
          {error}
        </div>
      )}

      <div className="trips-section">
        <h2 className="section-title">Upcoming Journeys</h2>
        {trips.filter(t => t.status === 'upcoming').length === 0 ? (
          <div className="empty-state-card">
            <h3>No upcoming trips</h3>
            <p>You haven't booked any journeys yet. Start your adventure today!</p>
            <button className="btn-primary-pro" onClick={() => navigate('/catalog')}>Find a Train</button>
          </div>
        ) : (
          <div className="trips-grid">
            {trips.filter(t => t.status === 'upcoming').map(trip => (
              <div key={trip.ticket_id} className="ticket-card upcoming">
                <div className="ticket-top">
                  <span className="ticket-id">{trip.ticket_id}</span>
                  <span className="ticket-badge-class">{trip.selected_class.toUpperCase()}</span>
                </div>
                <div className="ticket-route">
                  <div className="station">
                    <span className="station-code">{trip.origin.substring(0,3).toUpperCase()}</span>
                    <span className="station-name">{trip.origin}</span>
                  </div>
                  <div className="route-line"><span className="plane-icon">➔</span></div>
                  <div className="station alignment-right">
                    <span className="station-code">{trip.destination.substring(0,3).toUpperCase()}</span>
                    <span className="station-name">{trip.destination}</span>
                  </div>
                </div>
                <div className="ticket-details">
                  <div className="detail-item">
                    <span className="detail-label">DATE</span>
                    <span className="detail-value">{formatDate(trip.departure)}</span>
                  </div>
                  <div className="detail-item">
                    <span className="detail-label">DEPARTURE</span>
                    <span className="detail-value">{formatTime(trip.departure)}</span>
                  </div>
                  <div className="detail-item alignment-right">
                    <span className="detail-label">PRICE</span>
                    <span className="detail-value-price">{trip.price} {trip.currency}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};