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

  const formatCardNumber = (digits: string) => {
    return digits.replace(/\D/g, '').slice(0, 16).replace(/(.{4})/g, '$1 ').trim();
  };

  const formatExpiry = (digits: string) => {
    const clean = digits.replace(/\D/g, '').slice(0, 4);
    if (clean.length >= 3) return `${clean.slice(0, 2)}/${clean.slice(2)}`;
    return clean;
  };

  const handleConfirmBooking = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!keycloak.authenticated) {
      sessionStorage.setItem('post_login_redirect', location.pathname + location.search);
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

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString('en-GB', {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  if (bookingConfirmed) {
    return (
      <div className="checkout-viewport">
        <div className="success-card">
          <div className="success-badge">CONFIRMED</div>
          <h1>Booking Complete</h1>
          <p>Your reservation has been recorded. Access your ticket telemetry in the trips dashboard.</p>
          <div className="button-group">
            <button className="btn-secondary" onClick={() => navigate('/catalog')}>Return to Catalog</button>
            <button className="btn-primary" onClick={() => navigate('/my-trips')}>View My Trips</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="checkout-viewport">
      <div className="checkout-container">
        <header className="checkout-header">
          <span className="step-tag">CHECKOUT PROCESS</span>
          <h1>Review & Confirm</h1>
        </header>

        {error && <div className="checkout-error-banner">{error}</div>}
        
        <div className="checkout-layout">
          <section className="summary-column">
            <div className="summary-card">
              <h3>Journey Details</h3>
              <div className="route-header">
                <span>{origin}</span>
                <span className="route-separator">&rarr;</span>
                <span>{destination}</span>
              </div>
              
              <div className="train-meta">
                <div className="meta-row">
                  <span>Train ID</span>
                  <span className="mono">{trainId}</span>
                </div>
                <div className="meta-row">
                  <span>Departure</span>
                  <span>{formatDate(departure)}</span>
                </div>
                <div className="meta-row">
                  <span>Passengers</span>
                  <span>{passengers} {passengers === 1 ? 'Passenger' : 'Passengers'}</span>
                </div>
              </div>

              <hr className="divider" />

              <div className="class-selection">
                <label>Select Cabin Class</label>
                <div className="class-toggle">
                  <button 
                    type="button" 
                    className={`class-btn ${selectedClass === 'Standard' ? 'active' : ''}`} 
                    onClick={() => setSelectedClass('Standard')}
                  >
                    <span>Standard</span>
                    <small>Base fare</small>
                  </button>
                  <button 
                    type="button" 
                    className={`class-btn ${selectedClass === 'Business' ? 'active' : ''}`} 
                    onClick={() => setSelectedClass('Business')}
                  >
                    <span>Business</span>
                    <small>+50% fare</small>
                  </button>
                </div>
              </div>

              <hr className="divider" />

              <div className="price-breakdown">
                <div className="price-row">
                  <span>Base ticket ({passengers}x €{pricePerPassenger.toFixed(2)})</span>
                  <span>€{(pricePerPassenger * passengers).toFixed(2)}</span>
                </div>
                {selectedClass === 'Business' && (
                  <div className="price-row">
                    <span>Business class multiplier</span>
                    <span>1.5x</span>
                  </div>
                )}
                <div className="price-total">
                  <span>Total Due</span>
                  <span className="total-amount">€{totalAmount.toFixed(2)}</span>
                </div>
              </div>
            </div>
          </section>

          <section className="payment-column">
            <form className="payment-card" onSubmit={handleConfirmBooking}>
              <div className="payment-header">
                <h3>Payment Details</h3>
                <span className="security-tag">TLS 256-BIT ENCRYPTED</span>
              </div>
              
              <div className="form-group">
                <label htmlFor="card-number">Card Number</label>
                <input 
                  id="card-number"
                  type="text" 
                  inputMode="numeric"
                  placeholder="4532 0000 0000 0000" 
                  maxLength={19} 
                  value={formatCardNumber(cardData.number)} 
                  onChange={(e) => setCardData({...cardData, number: e.target.value.replace(/\D/g, '').slice(0, 16)})} 
                  required
                />
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label htmlFor="card-expiry">Expiration Date</label>
                  <input 
                    id="card-expiry"
                    type="text" 
                    inputMode="numeric"
                    placeholder="MM/YY" 
                    maxLength={5} 
                    value={formatExpiry(cardData.expiry)} 
                    onChange={(e) => setCardData({...cardData, expiry: e.target.value.replace(/\D/g, '').slice(0, 4)})} 
                    required
                  />
                </div>
                <div className="form-group">
                  <label htmlFor="card-cvc">Security Code</label>
                  <input 
                    id="card-cvc"
                    type="password" 
                    inputMode="numeric"
                    placeholder="CVC" 
                    maxLength={3} 
                    value={cardData.cvc} 
                    onChange={(e) => setCardData({...cardData, cvc: e.target.value.replace(/\D/g, '')})} 
                    required
                  />
                </div>
              </div>

              <button 
                type="submit" 
                className="btn-pay" 
                disabled={isSubmitting || cardData.number.length < 16 || cardData.expiry.length < 4 || cardData.cvc.length < 3}
              >
                {isSubmitting ? 'Processing Transaction...' : `Authorize & Pay €${totalAmount.toFixed(2)}`}
              </button>

              <p className="payment-disclaimer">
                By clicking Authorize, you confirm this booking under EuroTransit standard carrier terms.
              </p>
            </form>
          </section>
        </div>
      </div>
    </div>
  );
};