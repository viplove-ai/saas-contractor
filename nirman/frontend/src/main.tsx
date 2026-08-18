import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './app/App';
import { startDayAccentClock } from './app/dayAccent';

/*
  Before the first paint, deliberately. The accent is three custom properties on the document
  element, so writing them here means the button and the phone's top bar are the day's colour in
  the first frame rather than a frame later, and nothing re-renders to make it so.
*/
startDayAccentClock();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
