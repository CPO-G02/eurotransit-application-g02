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
  const [cardData, setCardData] = useState({ number: '', expiry: '', cvc: '' });

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
        idempotency_key: crypto.randomUUID(),
        user_id: keycloak.tokenParsed?.sub,
        user_email: keycloak.tokenParsed?.email,
        train_id: trainId,
        seat_class: selectedClass.toLowerCase(),
        quantity: passengers,
        amount: totalAmount,
        currency: 'EUR'
      }, {
        headers: { Authorization: `Bearer ${keycloak.token}` }
      });

      setBookingConfirmed(true);
    } catch (err: any) {
      setError(err.message || 'Failed to complete reservation.');
    } finally {
      setIsSubmitting(false);
    }
  };

  if (bookingConfirmed) {
    return (
      <div className="checkout-viewport">
        <div className="success-card">
          <div className="success-icon">✓</div>
          <h1>Booking Confirmed!</h1>
          <p>Your ticket is ready in your trips.</p>
          <button className="btn-confirm-booking" onClick={() => navigate('/my-trips')}>
            Go to My Trips
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="checkout-viewport">
      <div className="checkout-container">
        <h1>Confirm Your Journey</h1>
        {error && <div className="checkout-error-banner">{error}</div>}
        
        <div className="checkout-grid">
          <div className="journey-summary-card">
            <h3>Journey</h3>
            <div className="summary-route">{origin} ➔ {destination}</div>
            <div className="class-selector">
              <button className={`class-btn ${selectedClass === 'Standard' ? 'active' : ''}`} onClick={() => setSelectedClass('Standard')}>Standard</button>
              <button className={`class-btn ${selectedClass === 'Business' ? 'active' : ''}`} onClick={() => setSelectedClass('Business')}>Business</button>
            </div>
          </div>

          <div className="payment-summary-card">
            <h3>Payment Details</h3>
            <input placeholder="Card Number (16 digits)" maxLength={16} value={cardData.number} onChange={(e) => setCardData({...cardData, number: e.target.value.replace(/\D/g, '')})} />
            <div className="card-row">
              <input placeholder="MM/YY" maxLength={5} value={cardData.expiry} onChange={(e) => setCardData({...cardData, expiry: e.target.value})} />
              <input placeholder="CVC" maxLength={3} value={cardData.cvc} onChange={(e) => setCardData({...cardData, cvc: e.target.value.replace(/\D/g, '')})} />
            </div>

            <button className="btn-confirm-booking" onClick={handleConfirmBooking} disabled={isSubmitting || cardData.number.length < 16}>
              {isSubmitting ? 'Processing...' : `Pay €${totalAmount.toFixed(2)} & Book`}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};