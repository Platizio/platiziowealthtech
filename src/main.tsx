import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import { Provider } from 'react-redux';
import App from './App.tsx';
import { BrowserRouter } from 'react-router-dom';
import { store } from './store';
import './index.css';

// HttpOnly auth cookies are host-specific — 127.0.0.1 and localhost do not share cookies.
if (typeof window !== 'undefined' && window.location.hostname === '127.0.0.1') {
  window.location.replace(
    window.location.href.replace('//127.0.0.1', '//localhost'),
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Provider store={store}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </Provider>
  </StrictMode>,
);
