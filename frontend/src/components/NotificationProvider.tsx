import { useEffect } from 'react';
import { Client } from '@stomp/stompjs';
import toast from 'react-hot-toast';
import { useKeycloak } from '@react-keycloak/web';

export const NotificationProvider = ({ children }: { children: React.ReactNode }) => {
  const { keycloak } = useKeycloak();

  useEffect(() => {
    if (!keycloak.authenticated || !keycloak.tokenParsed?.email) return;

    const email = keycloak.tokenParsed.email;
    const wsUrl = 'wss://g02.cpo2026.it/ws';

    const client = new Client({
      brokerURL: wsUrl,
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe(`/topic/notifications/${email}`, (message) => {
          const event = JSON.parse(message.body);

          if (event.reason) {
            toast.error(`Booking Failed: ${event.reason}`, {
              duration: 6000,
              position: 'top-right',
            });
          } else if (event.transaction_id || event.amount) {
            toast.success(`Booking Confirmed! Order #${event.order_id} (€${event.amount})`, {
              duration: 6000,
              position: 'top-right',
            });
          } else {
            toast('New update regarding your trip!', {
              position: 'top-right',
            });
          }
        });
      },
    });

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [keycloak.authenticated, keycloak.tokenParsed?.email]);

  return <>{children}</>;
};