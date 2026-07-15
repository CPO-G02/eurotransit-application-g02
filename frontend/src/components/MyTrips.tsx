import { useEffect, useState } from 'react';
import { useKeycloak } from '@react-keycloak/web';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import type { OrderStatusResponse } from '../types/eurotransit';
import './MyTrips.css';

export const MyTrips = () => {
  const { keycloak, initialized } = useKeycloak();
  const navigate = useNavigate();
  const [orders, setOrders] = useState<OrderStatusResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!initialized) return;

    if (!keycloak.authenticated) {
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    const fetchOrders = async () => {
      try {
        await keycloak.updateToken(30);
      } catch {
        if (!cancelled) {
          setError('Your session expired. Please sign in again.');
          setLoading(false);
        }
        return;
      }

      try {
        const response = await api.get<OrderStatusResponse[]>('/orders', {
          headers: { Authorization: `Bearer ${keycloak.token}` }
        });
        if (!cancelled) setOrders(response.data || []);
      } catch (err: any) {
        if (cancelled) return;
        if (err.response && err.response.status === 404) {
          setOrders([]);
        } else if (err.response && (err.response.status === 401 || err.response.status === 403)) {
          setError('Your session could not be verified. Try signing in again.');
        } else {
          setError('Unable to retrieve telemetry data from the transport network.');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    fetchOrders();

    return () => {
      cancelled = true;
    };
  }, [initialized, keycloak, keycloak.authenticated]);

  const formatDate = (dateString?: string) => {
    if (!dateString) return 'N/A';
    const date = new Date(dateString);
    return date.toLocaleDateString('en-GB', { 
      weekday: 'short', 
      day: 'numeric', 
      month: 'short', 
      hour: '2-digit', 
      minute: '2-digit' 
    });
  };

  if (!initialized || loading) {
    return (
      <div className="trips-viewport">
        <div className="state-container">
          <p className="state-text">Synchronizing records...</p>
        </div>
      </div>
    );
  }

  if (!keycloak.authenticated) {
    return (
      <div className="trips-viewport">
        <div className="state-card">
          <span className="state-tag">AUTHENTICATION REQUIRED</span>
          <h1>Sign in to view your journeys</h1>
          <p>Access your real-time e-tickets, track network schedules, and manage your booking history.</p>
          <button className="btn-action" onClick={() => keycloak.login()}>Sign In</button>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="trips-viewport">
        <div className="state-card error-border">
          <span className="state-tag error-tag">SYSTEM NOTICE</span>
          <h1>Connection Error</h1>
          <p>{error}</p>
          <button className="btn-action" onClick={() => window.location.reload()}>Retry Connection</button>
        </div>
      </div>
    );
  }

  return (
    <div className="trips-viewport">
      <div className="trips-container">
        <header className="trips-header">
          <div>
            <span className="step-tag">PASSENGER TELEMETRY</span>
            <h1>Booked Journeys</h1>
          </div>
          <div className="user-badge">
            <span>Account:</span>
            <strong>{keycloak.tokenParsed?.preferred_username || "Authorized User"}</strong>
          </div>
         </header>

        {orders.length === 0 ? (
          <div className="state-card empty-state">
            <span className="state-tag">NO RECORD FOUND</span>
            <h2>No active bookings</h2>
            <p>You have not scheduled any transport across the EuroTransit network yet.</p>
            <button className="btn-action" onClick={() => navigate('/catalog')}>Explore Catalog</button>
          </div>
        ) : (
          <div className="orders-list">
            {orders.map(order => (
              <div key={order.order_id} className="order-card">
                <div className="order-header">
                  <div className="order-id-group">
                    <span className="label">ORDER ID</span>
                    <span className="mono">{order.order_id}</span>
                  </div>
                  <span className={`status-badge ${order.status?.toLowerCase() || 'confirmed'}`}>
                    {order.status || 'CONFIRMED'}
                  </span>
                </div>
                
                <div className="order-grid">
                  <div className="grid-item">
                    <span className="label">ASSIGNED TRAIN</span>
                    <span className="value">{order.train_id}</span>
                  </div>
                  <div className="grid-item">
                    <span className="label">CABIN CLASS</span>
                    <span className="value">{order.seat_class ? order.seat_class.toUpperCase() : 'STANDARD'}</span>
                  </div>
                  <div className="grid-item">
                    <span className="label">PASSENGERS</span>
                    <span className="value">{order.quantity}</span>
                  </div>
                  <div className="grid-item">
                    <span className="label">TRANSACTION TIMESTAMP</span>
                    <span className="value">{formatDate(order.created_at)}</span>
                  </div>
                </div>

                <div className="order-footer">
                  <span className="label">TOTAL FARE SETTLED</span>
                  <span className="price">€{Number(order.amount).toFixed(2)} {order.currency || 'EUR'}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
