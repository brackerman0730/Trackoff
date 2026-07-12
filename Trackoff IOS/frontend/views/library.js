import { el, header, nav, apiGet, toast, spinner } from '../core.js';
import { showDestinationChooser } from '../chooser.js';

export async function render(container) {
  container.appendChild(header('Your Library', { onBack: () => nav('main') }));
  const loading = spinner();
  container.appendChild(loading);

  let playlists;
  try {
    playlists = await apiGet('/api/library/playlists');
  } catch (e) {
    loading.remove();
    container.appendChild(el('p', {}, 'Could not load playlists.'));
    container.appendChild(el('p', { class: 'label-muted' }, e.message));
    if (String(e.message).toLowerCase().includes('not logged in')) {
      container.appendChild(el('button', { class: 'btn-primary btn-block', onclick: () => nav('connect-spotify') }, 'Connect Spotify'));
    }
    return;
  }
  loading.remove();

  if (playlists.length === 0) {
    container.appendChild(el('p', { class: 'label-muted' }, 'No playlists found.'));
    return;
  }

  const grid = el('div', { class: 'playlist-grid' });
  for (const p of playlists) {
    grid.appendChild(tile(p));
  }
  container.appendChild(grid);
}

function tile(p) {
  const art = p.imageUrl
      ? el('img', { src: p.imageUrl, loading: 'lazy' })
      : el('div', { class: 'placeholder' }, '♪');

  const t = el('div', { class: 'playlist-tile' }, [
    art,
    el('div', { class: 'info' }, [
      el('div', { class: 'name' }, p.name),
      el('div', { class: 'meta' }, `${p.trackCount} tracks${p.isOwned ? '' : ' · ' + p.ownerName}`),
    ]),
  ]);

  t.onclick = async () => {
    t.style.opacity = '0.5';
    try {
      const songs = await apiGet(`/api/library/playlist?id=${encodeURIComponent(p.id)}`);
      t.style.opacity = '1';
      showDestinationChooser(p.name, songs, p.id);
    } catch (e) {
      t.style.opacity = '1';
      toast(e.message, true);
    }
  };
  return t;
}
