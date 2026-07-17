import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useKeycloak } from '@react-keycloak/web';
import api from '../api/client';
import type { OrderStatusResponse } from '../types/eurotransit';
import './Checkout.css';

const SUCCESS_STATUSES = ['confirmed', 'completed', 'paid'];
const FAILURE_STATUSES = ['failed', 'declined', 'cancelled', 'canceled'];
const POLL_INTERVAL_MS = 2000;
const POLL_TIMEOUT_MS = 30000;

export const Checkout = () => {
  const { trainId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { keycloak } = useKeycloak();

  const queryParams = new URLSearchParams(location.search);
  const origin = queryParams.get('origin') || 'Milano';
  const destination = queryParams.get('destination') || 'Roma';
  const departure = queryParams.get('departure') || '';
  const passengers = parseInt(queryParams.get('passengers') || '1', 10);
  const pricePerPassenger = parseFloat(queryParams.get('price') || '45.00');

  const [selectedClass, setSelectedClass] = useState<'Standard' | 'Business'>('Standard');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [bookingState, setBookingState] = useState<'form' | 'processing' | 'confirmed' | 'failed'>('form');
  const [confirmedOrder, setConfirmedOrder] = useState<any>(null);
  const [cardData, setCardData] = useState({ holderName: '', number: '', expiry: '', cvc: '' });
  const cancelledRef = useRef(false);

  useEffect(() => {
    return () => {
      cancelledRef.current = true;
    };
  }, []);

  const multiplier = selectedClass === 'Business' ? 1.5 : 1.0;
  const totalAmount = pricePerPassenger * passengers * multiplier;
  const hasDeparted = departure ? new Date(departure).getTime() < Date.now() : false;

  const formatCardNumber = (digits: string) => {
    return digits.replace(/\D/g, '').slice(0, 16).replace(/(.{4})/g, '$1 ').trim();
  };

  const sanitizeExpiryDigits = (rawDigits: string) => {
    let digits = rawDigits.replace(/\D/g, '').slice(0, 4);

    if (digits.length === 1) {
      if (parseInt(digits, 10) > 1) {
        digits = `0${digits}`;
      }
    } else if (digits.length >= 2) {
      const month = parseInt(digits.slice(0, 2), 10);
      if (month < 1) {
        digits = `01${digits.slice(2)}`;
      } else if (month > 12) {
        digits = `0${digits[0]}${digits.slice(1)}`.slice(0, 4);
      }
    }

    return digits;
  };

  const formatExpiry = (digits: string) => {
    const clean = digits.slice(0, 4);
    if (clean.length >= 3) return `${clean.slice(0, 2)}/${clean.slice(2)}`;
    return clean;
  };

  const getExpiryError = (digits: string): string | null => {
    if (digits.length < 4) return null;
    const month = parseInt(digits.slice(0, 2), 10);
    const year = 2000 + parseInt(digits.slice(2, 4), 10);
    const now = new Date();
    const expiryEnd = new Date(year, month, 0, 23, 59, 59);
    if (expiryEnd < now) return 'This card has expired.';
    return null;
  };

  const expiryError = getExpiryError(cardData.expiry);

  const pollForOutcome = async (orderId: string, token: string, deadline: number) => {
    if (cancelledRef.current) return;

    if (Date.now() > deadline) {
      setError('We could not confirm your payment in time. Please check My Trips for the latest status before trying again.');
      setBookingState('failed');
      return;
    }

    try {
      const res = await api.get<OrderStatusResponse[]>('/orders', {
        headers: { Authorization: `Bearer ${token}` }
      });
      const match = (res.data || []).find((o) => o.order_id === orderId);
      const status = match?.status?.toLowerCase();

      if (status && SUCCESS_STATUSES.includes(status)) {
        setConfirmedOrder(match);
        setBookingState('confirmed');
        return;
      }

      if (status && FAILURE_STATUSES.includes(status)) {
        setError('Your payment could not be processed. Please check your card details and try again.');
        setBookingState('failed');
        return;
      }
    } catch {
    }

    if (!cancelledRef.current) {
      setTimeout(() => pollForOutcome(orderId, token, deadline), POLL_INTERVAL_MS);
    }
  };

  const handleConfirmBooking = async (e: React.FormEvent) => {
    e.preventDefault();
    if (hasDeparted) {
      setError('This train has already departed and can no longer be booked.');
      return;
    }
    if (!keycloak.authenticated) {
      sessionStorage.setItem('post_login_redirect', location.pathname + location.search);
      keycloak.login();
      return;
    }

    setIsSubmitting(true);
    setError(null);

    try {
      const response = await api.post('/orders', {
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

      const orderId = response.data?.order_id || response.data?.id;
      const initialStatus = response.data?.status?.toLowerCase();

      setIsSubmitting(false);

      if (initialStatus && FAILURE_STATUSES.includes(initialStatus)) {
        setError('Your payment could not be processed. Please check your card details and try again.');
        setBookingState('failed');
        return;
      }

      if (initialStatus && SUCCESS_STATUSES.includes(initialStatus)) {
        setConfirmedOrder(response.data);
        setBookingState('confirmed');
        return;
      }

      if (!orderId || !keycloak.token) {
        setError('Booking submitted, but we could not verify the outcome automatically. Please check My Trips shortly.');
        setBookingState('failed');
        return;
      }

      setBookingState('processing');
      pollForOutcome(orderId, keycloak.token, Date.now() + POLL_TIMEOUT_MS);
    } catch (err: any) {
      setError(err.message || 'Failed to complete reservation.');
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

  if (bookingState === 'processing') {
    return (
      <div className="checkout-viewport success-view">
        <div className="success-card">
          <div className="success-badge pending">PROCESSING</div>
          <h1>Confirming your payment&hellip;</h1>
          <p>We're verifying your payment with the network. This usually takes a few seconds - please don't close this page.</p>
        </div>
      </div>
    );
  }

  if (bookingState === 'failed') {
    return (
      <div className="checkout-viewport success-view">
        <div className="success-card">
          <div className="success-badge failed">PAYMENT FAILED</div>
          <h1>We couldn't complete this booking</h1>
          <p>{error || 'Your payment could not be processed.'}</p>
          <div className="button-group">
            <button className="btn-secondary" onClick={() => navigate('/my-trips')}>Check My Trips</button>
            <button className="btn-primary" onClick={() => { setError(null); setBookingState('form'); }}>Try Again</button>
          </div>
        </div>
      </div>
    );
  }

  if (bookingState === 'confirmed') {
    const orderReference = confirmedOrder?.order_id || confirmedOrder?.id || trainId;
    const cardLast4 = cardData.number.slice(-4);

    return (
      <div className="checkout-viewport success-view">
        <div className="success-card">
          <div className="success-badge">CONFIRMED</div>
          <h1>Booking Complete</h1>
          <p>Your reservation has been recorded. A confirmation will follow to your account shortly.</p>

          <div className="order-summary">
            <div className="order-summary-row">
              <span>Order reference</span>
              <span className="mono">{orderReference}</span>
            </div>
            <div className="order-summary-row">
              <span>Route</span>
              <span>{origin} &rarr; {destination}</span>
            </div>
            <div className="order-summary-row">
              <span>Train</span>
              <span className="mono">{trainId}</span>
            </div>
            <div className="order-summary-row">
              <span>Departure</span>
              <span>{formatDate(departure)}</span>
            </div>
            <div className="order-summary-row">
              <span>Cabin class</span>
              <span>{selectedClass}</span>
            </div>
            <div className="order-summary-row">
              <span>Passengers</span>
              <span>{passengers}</span>
            </div>
            <div className="order-summary-row">
              <span>Cardholder</span>
              <span>{cardData.holderName}</span>
            </div>
            <div className="order-summary-row">
              <span>Paid with</span>
              <span className="mono">&bull;&bull;&bull;&bull; {cardLast4}</span>
            </div>
            <div className="order-summary-total">
              <span>Total paid</span>
              <span className="total-amount">€{totalAmount.toFixed(2)}</span>
            </div>
          </div>

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
          <button className="btn-back" onClick={() => navigate('/catalog')}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M15 6l-6 6 6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            All trains
          </button>
          <span className="step-tag">CHECKOUT PROCESS</span>
          <h1>Review & Confirm</h1>
        </header>

        {(error || hasDeparted) && (
          <div className="checkout-error-banner">
            {error || 'This train has already departed. Please choose a different connection.'}
          </div>
        )}
        
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
                <label htmlFor="card-holder">Cardholder Name</label>
                <input
                  id="card-holder"
                  type="text"
                  placeholder="MARIO ROSSI"
                  value={cardData.holderName}
                  onChange={(e) => setCardData({ ...cardData, holderName: e.target.value.toUpperCase() })}
                  required
                />
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
                    onChange={(e) => setCardData({...cardData, expiry: sanitizeExpiryDigits(e.target.value)})} 
                    aria-invalid={!!expiryError}
                    required
                  />
                  {expiryError && <span className="field-error">{expiryError}</span>}
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
                disabled={isSubmitting || bookingState !== 'form' || hasDeparted || !cardData.holderName.trim() || cardData.number.length < 16 || cardData.expiry.length < 4 || !!expiryError || cardData.cvc.length < 3}
              >
                {hasDeparted ? 'Train departed' : isSubmitting ? 'Submitting...' : `Authorize & Pay €${totalAmount.toFixed(2)}`}
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