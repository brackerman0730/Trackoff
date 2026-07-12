// Shared bottom-sheet "what do you want to do with this playlist?"
// destination chooser — used by both Library and CSV Import.
import { el, nav } from './core.js';
import { setCurrentPlaylist } from './state.js';

export function showDestinationChooser(name, songs, playlistId) {
  const backdrop = el('div', { class: 'sheet-backdrop', onclick: close });
  const sheet = el('div', { class: 'sheet' }, [
    el('h3', {}, name),
    el('p', { class: 'label-muted', style: 'margin-bottom:14px' }, `${songs.length} tracks`),
    destButton('Rank', () => go('rank')),
    destButton('Swipe', () => go('swipe')),
    destButton('Tier List', () => go('tierlist')),
    destButton('Last.fm Manager', () => go('lastfm-manager')),
    el('button', { class: 'btn-ghost btn-block', onclick: close }, 'Cancel'),
  ]);
  document.body.appendChild(backdrop);
  document.body.appendChild(sheet);

  function close() { backdrop.remove(); sheet.remove(); }
  function go(route) {
    if (songs.length < 1) { close(); return; }
    setCurrentPlaylist(name, songs, playlistId);
    close();
    nav(route);
  }
}

function destButton(label, onclick) {
  return el('button', { class: 'btn-secondary btn-block', onclick }, label);
}
