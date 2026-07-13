import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useKeycloak } from '@react-keycloak/web';
import api from '../api/client';
import './Checkout.css';

export const Checkout = () => {
  const { trainId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { keycloak } = useKeycloak();

  const queryParams = new URLSearchParams(location.search);
  const origin = queryParams.get('origin') || 'Milano';
  const destination = queryParams.get('destination') || 'Roma';
  const departure = queryParams.get('departure') || new Date().toISOString();
  const passengers = parseInt(queryParams.get('passengers') || '1', 10);
  const pricePerPassenger = parseFloat(queryParams.get('price') || '45.00');

  const [selectedClass, setSelectedClass] = useState<'Standard' | 'Business'>('Standard');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [bookingConfirmed, setBookingConfirmed] = useState(false);

  const multiplier = selectedClass === 'Business' ? 1.5 : 1.0;
  const totalAmount = pricePerPassenger * passengers * multiplier;

  const handleConfirmBooking = async () => {
    if (!keycloak.authenticated) {
      keycloak.login();
      return;
    }

    setIsSubmitting(true);
    setError(null);

    try {
      await api.post('/orders', {
        trainId,
        origin,
        destination,
        departure,
        passengers,
        seatClass: selectedClass,
        totalAmount,
        currency: 'EUR',
        userId: keycloak.tokenParsed?.sub
      }, {
        headers: {
          Authorization: `Bearer ${keycloak.token}`
        }
      });

      setIsSubmitting(false);
      setBookingConfirmed(true);
    } catch (err: any) {
      setIsSubmitting(false);
      setError(err.message || 'Failed to complete reservation. Please try again.');
    }
  };

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString('en-GB', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  if (bookingConfirmed) {
    return (
      <div className="checkout-viewport">
        <div className="success-card">
          <div className="success-icon">✓</div>
          <h1>Booking Confirmed!</h1>
          <p>Your train ticket is ready to use in your dashboard.</p>
          <div className="button-group">
            <button className="btn-secondary-pro" onClick={() => navigate('/catalog')}>
              Back to Catalog
            </button>
            <button className="btn-confirm-booking" onClick={() => navigate('/my-trips')}>
              Go to My Tickets
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="checkout-viewport">
      <div className="checkout-container">
        <div className="checkout-header">
          <h1>Confirm Your Journey</h1>
          <p>Review details and complete your EuroTransit reservation.</p>
        </div>

        {error && <div className="checkout-error-banner">{error}</div>}

        <div className="checkout-grid">
          <div className="journey-summary-card">
            <h2>Journey Summary</h2>
            <div className="summary-route">
              <span>{origin}</span>
              <span className="summary-arrow">➔</span>
              <span>{destination}</span>
            </div>
            <div className="summary-details">
              <div className="summary-row">
                <span>Train ID</span>
                <strong>{trainId}</strong>
              </div>
              <div className="summary-row">
                <span>Departure</span>
                <strong>{formatDate(departure)}</strong>
              </div>
              <div className="summary-row">
                <span>Passengers</span>
                <strong>{passengers}</strong>
              </div>
            </div>

            <div className="class-selector">
              <h3>Select Class</h3>
              <div className="class-options">
                <button
                  type="button"
                  className={`class-btn ${selectedClass === 'Standard' ? 'active' : ''}`}
                  onClick={() => setSelectedClass('Standard')}
                >
                  <span>Standard</span>
                  <small>1x Rate</small>
                </button>
                <button
                  type="button"
                  className={`class-btn ${selectedClass === 'Business' ? 'active' : ''}`}
                  onClick={() => setSelectedClass('Business')}
                >
                  <span>Business</span>
                  <small>1.5x Rate</small>
                </button>
              </div>
            </div>
          </div>

          <div className="payment-summary-card">
            <h2>Price Breakdown</h2>
            <div className="breakdown-row">
              <span>Base rate ({passengers}x €{pricePerPassenger.toFixed(2)})</span>
              <span>€{(pricePerPassenger * passengers).toFixed(2)}</span>
            </div>
            <div className="breakdown-row">
              <span>Class upgrade ({selectedClass})</span>
              <span>x{multiplier}</span>
            </div>
            <hr className="breakdown-divider" />
            <div className="breakdown-total">
              <span>Total Amount</span>
              <span className="total-price">€{totalAmount.toFixed(2)}</span>
            </div>

            <button
              className="btn-confirm-booking"
              onClick={handleConfirmBooking}
              disabled={isSubmitting}
            >
              {isSubmitting ? 'Processing...' : `Pay €${totalAmount.toFixed(2)} & Book`}
            </button>
            
            {!keycloak.authenticated && (
              <p className="login-hint">
                * You will be redirected to login before confirming your booking.
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};