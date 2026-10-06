/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

test('renders correctly', async () => {
  let tree: ReactTestRenderer.ReactTestRenderer | undefined;

  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<App />);
  });

  // App setInterval (IP refresh) aur AppState listener lagata hai.
  // Unmount in sab ka cleanup chalata hai, warna Jest process exit nahi hota.
  await ReactTestRenderer.act(async () => {
    tree?.unmount();
  });
});
