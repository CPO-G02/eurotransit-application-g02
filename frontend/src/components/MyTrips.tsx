import { useEffect, useState } from 'react';
import { useKeycloak } from '@react-keycloak/web';
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
        setError(null);

        const response = await fetch('https://g02.cpo2026.it/api/v1/orders', {
          headers: {
            'Authorization': `Bearer ${keycloak.token}`,
            'Content-Type': 'application/json'
          }
        });

        if (!response.ok) {
          throw new Error(`Error fetching trips: ${response.statusText}`);
        }

        const data: Trip[] = await response.json();
        setTrips(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'An unknown error occurred');
      } finally {
        setLoading(false);
      }
    };

    fetchTrips();
  }, [initialized, keycloak.authenticated, keycloak.token]);

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('ca-ES', { weekday: 'short', day: 'numeric', month: 'short' });
  };

  const formatTime = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleTimeString('ca-ES', { hour: '2-digit', minute: '2-digit' });
  };

  if (!initialized || loading) {
    return (
      <div className="trips-container">
        <div className="trips-loading">Loading your dashboard...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="trips-container">
        <div className="trips-card-error">
          <h2>Error Loading Trips</h2>
          <p>{error}</p>
          <button className="btn-primary-pro" onClick={() => window.location.reload()}>Retry</button>
        </div>
      </div>
    );
  }

  if (!keycloak.authenticated) {
    return (
      <div className="trips-container">
        <div className="trips-card-error">
          <h2>Access Restricted</h2>
          <p>Please log in with your EuroTransit account to view your booked trips.</p>
          <button className="btn-primary-pro" onClick={() => keycloak.login()}>Log In Now</button>
        </div>
      </div>
    );
  }

  return (
    <div className="trips-container">
      <div className="trips-header">
        <h1 className="trips-title">My Dashboard</h1>
        <p className="trips-subtitle">
          Logged in as: <span className="user-highlight">{keycloak?.tokenParsed?.preferred_username || "Guest"}</span>
        </p>
      </div>

      <div className="trips-section">
        <h2 className="section-title">Upcoming Journeys</h2>
        {trips.filter(t => t.status === 'upcoming').length === 0 ? (
          <p className="no-trips">No upcoming trips found.</p>
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
                  <div className="route-line">
                    <span className="plane-icon">➔</span>
                  </div>
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

      <div className="trips-section history-section">
        <h2 className="section-title">Past Trips</h2>
        {trips.filter(t => t.status === 'completed').length === 0 ? (
          <p className="no-trips">No past trips found.</p>
        ) : (
          <div className="trips-grid">
            {trips.filter(t => t.status === 'completed').map(trip => (
              <div key={trip.ticket_id} className="ticket-card completed">
                <div className="ticket-top">
                  <span className="ticket-id">{trip.ticket_id}</span>
                  <span className="ticket-badge-completed">ARCHIVED</span>
                </div>
                <div className="ticket-route">
                  <span className="past-route-text">{trip.origin} to {trip.destination}</span>
                  <span className="past-date-text">{formatDate(trip.departure)}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};