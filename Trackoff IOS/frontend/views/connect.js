import { el, header, nav, back, apiGet, apiPost, toast } from '../core.js';

export async function renderSpotify(container) {
  container.appendChild(header('Connect Spotify', { onBack: () => nav('main') }));

  const status = await apiGet('/api/status').catch(() => ({}));

  if (status.spotifyLinked) {
    container.appendChild(el('div', { class: 'card' }, [
      el('p', {}, '✓ Connected'),
      el('p', { class: 'label-muted' }, 'Your Spotify account is linked. Log out below to switch accounts.'),
    ]));
    const disconnectBtn = el('button', { class: 'btn-danger btn-block' }, 'Disconnect');
    disconnectBtn.onclick = async () => {
      await apiPost('/api/settings/spotify/disconnect', {});
      toast('Disconnected');
      nav('main');
    };
    container.appendChild(disconnectBtn);
    return;
  }

  container.appendChild(el('div', { class: 'card' }, [
    el('p', { class: 'label-muted' }, status.spotifyConfigured
        ? 'A Spotify app is already configured (reused from the desktop app). Tap below to log in.'
        : 'Enter your Spotify app\'s Client ID and Secret from developer.spotify.com/dashboard. Add this exact redirect URI there first: '),
    !status.spotifyConfigured ? el('p', { html: `<code>${window.location.origin}/api/spotify/callback</code>` }) : null,
  ]));

  if (!status.spotifyConfigured) {
    const idInput = el('input', { type: 'text', placeholder: 'Client ID' });
    const secretInput = el('input', { type: 'password', placeholder: 'Client Secret' });
    const saveBtn = el('button', { class: 'btn-secondary btn-block' }, 'Save credentials');
    saveBtn.onclick = async () => {
      if (!idInput.value.trim() || !secretInput.value.trim()) { toast('Enter both fields', true); return; }
      try {
        await apiPost('/api/settings/spotify', { clientId: idInput.value.trim(), clientSecret: secretInput.value.trim() });
        toast('Saved — now tap Log in with Spotify');
        render();
      } catch (e) { toast(e.message, true); }
    };
    container.appendChild(idInput);
    container.appendChild(secretInput);
    container.appendChild(saveBtn);
    container.appendChild(el('div', { style: 'height:12px' }));
  }

  const loginBtn = el('button', { class: 'btn-primary btn-block' }, 'Log in with Spotify');
  loginBtn.onclick = () => { window.location.href = '/api/spotify/login'; };
  container.appendChild(loginBtn);

  async function render() {
    container.innerHTML = '';
    await renderSpotify(container);
  }
}

export async function renderLastFm(container) {
  container.appendChild(header('Connect Last.fm', { onBack: () => nav('main') }));

  const status = await apiGet('/api/status').catch(() => ({}));

  if (status.lastfmLinked) {
    container.appendChild(el('div', { class: 'card' }, [
      el('p', {}, `✓ Connected as ${status.lastfmUsername}`),
    ]));
    const disconnectBtn = el('button', { class: 'btn-danger btn-block' }, 'Disconnect');
    disconnectBtn.onclick = async () => {
      await apiPost('/api/settings/lastfm/disconnect', {});
      toast('Disconnected');
      nav('main');
    };
    container.appendChild(disconnectBtn);
    return;
  }

  container.appendChild(el('div', { class: 'card' }, [
    el('p', { class: 'label-muted' }, 'Enter your Last.fm username and an API key from last.fm/api/account/create.'),
  ]));

  const userInput = el('input', { type: 'text', placeholder: 'Username' });
  const keyInput = el('input', { type: 'text', placeholder: 'API Key' });
  const saveBtn = el('button', { class: 'btn-primary btn-block' }, 'Connect');
  saveBtn.onclick = async () => {
    if (!userInput.value.trim() || !keyInput.value.trim()) { toast('Enter both fields', true); return; }
    saveBtn.disabled = true;
    saveBtn.textContent = 'Checking…';
    try {
      await apiPost('/api/settings/lastfm', { username: userInput.value.trim(), apiKey: keyInput.value.trim() });
      toast('Connected!');
      nav('main');
    } catch (e) {
      toast(e.message, true);
      saveBtn.disabled = false;
      saveBtn.textContent = 'Connect';
    }
  };
  container.appendChild(userInput);
  container.appendChild(keyInput);
  container.appendChild(saveBtn);
}
