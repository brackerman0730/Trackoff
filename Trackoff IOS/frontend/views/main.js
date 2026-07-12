import { el, header, nav, apiGet, toast } from '../core.js';

export async function render(container) {
  container.appendChild(header('Trackoff'));

  const status = await apiGet('/api/status').catch(() => null);

  const strip = el('div', { class: 'status-strip' }, [
    el('span', { class: 'label-muted' }, status?.spotifyLinked ? '✓ Spotify connected' : 'Spotify not connected'),
    el('span', { class: 'label-muted' }, status?.lastfmLinked ? `✓ Last.fm (${status.lastfmUsername})` : 'Last.fm not connected'),
  ]);
  container.appendChild(strip);

  const menu = el('div', { class: 'menu-list' });

  menu.appendChild(menuItem('📚', 'My Library', 'Browse your Spotify playlists', () => nav('library')));
  menu.appendChild(menuItem('📄', 'Import CSV', 'Rank a playlist from a CSV export', () => nav('csv-import')));
  menu.appendChild(menuItem('🎧', 'Connect Spotify', status?.spotifyLinked ? 'Connected — manage' : 'Required for Library', () => nav('connect-spotify')));
  menu.appendChild(menuItem('📻', 'Connect Last.fm', status?.lastfmLinked ? `Connected as ${status.lastfmUsername}` : 'Optional — enables play counts', () => nav('connect-lastfm')));

  container.appendChild(menu);

  if (!status) toast('Could not reach the server — is it running?', true);
}

function menuItem(icon, title, sub, onclick) {
  return el('div', { class: 'menu-item', onclick }, [
    el('div', { class: 'icon' }, icon),
    el('div', {}, [
      el('div', { class: 'title' }, title),
      el('div', { class: 'sub' }, sub),
    ]),
    el('div', { class: 'chevron' }, '›'),
  ]);
}
