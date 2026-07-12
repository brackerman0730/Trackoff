import { el, header, nav, apiGet, apiPost, playPreview, stopAudio, isAudioPlaying, pausePreview, resumePreview, toast } from '../core.js';
import { getCurrentPlaylist } from '../state.js';

export async function render(container) {
  const { name, songs } = getCurrentPlaylist();
  if (!songs || songs.length < 1) {
    container.appendChild(header('Last.fm Manager', { onBack: () => nav('main') }));
    container.appendChild(el('p', {}, 'No songs. Go back and pick a playlist.'));
    return;
  }

  container.appendChild(header(name, { onBack: () => { stopAudio(); nav('main'); } }));
  container.appendChild(el('p', { class: 'label-muted' }, `${songs.length} tracks · Last.fm play counts`));

  const sortBtn = el('button', { class: 'btn-ghost', style: 'margin:10px 0' }, 'Sort: Most Played');
  container.appendChild(sortBtn);

  const list = el('div', {});
  container.appendChild(list);

  let sortByPlays = false;
  let playingId = null;
  const rows = songs.map(s => ({ song: s, playcount: -1, resolved: false, previewUrl: undefined, el: null }));
  let runningMax = 0;

  sortBtn.onclick = () => {
    sortByPlays = !sortByPlays;
    sortBtn.textContent = sortByPlays ? 'Sort: Playlist Order' : 'Sort: Most Played';
    renderList();
  };

  function renderList() {
    list.innerHTML = '';
    const order = sortByPlays ? rows.slice().sort((a, b) => b.playcount - a.playcount) : rows;
    order.forEach((r, i) => {
      const rowEl = buildRow(r, i + 1);
      r.el = rowEl;
      list.appendChild(rowEl);
    });
  }

  function buildRow(r, idx) {
    const song = r.song;
    const art = song.imageUrl ? el('img', { class: 'art', src: song.imageUrl }) : el('div', { class: 'art' }, [el('div', { class: 'placeholder' }, '♪')]);
    const playBtn = el('button', { class: 'play-mini' }, '▶');
    art.style.position = 'relative';
    art.appendChild(playBtn);
    playBtn.onclick = () => togglePlay(r, playBtn);

    const fill = el('div', { class: 'bar-fill' });
    const countLabel = el('div', { class: 'plays' + (r.manual ? ' manual' : '') }, r.resolved ? `${r.playcount.toLocaleString()} plays` : '…');

    const row = el('div', { class: 'playcount-row' }, [
      el('div', { class: 'idx' }, String(idx)),
      art,
      el('div', { class: 'info' }, [
        el('div', { class: 'title' }, song.title),
        el('div', { class: 'artist' }, song.artist),
      ]),
      el('div', { class: 'bar-wrap' }, [
        el('div', { class: 'bar-track' }, [fill]),
        countLabel,
      ]),
    ]);
    row._fill = fill;
    row._countLabel = countLabel;

    let pressTimer = null;
    row.addEventListener('pointerdown', () => { pressTimer = setTimeout(() => showReassignMenu(r), 500); });
    row.addEventListener('pointerup', () => clearTimeout(pressTimer));
    row.addEventListener('pointercancel', () => clearTimeout(pressTimer));

    return row;
  }

  function showReassignMenu(r) {
    const backdrop = el('div', { class: 'sheet-backdrop', onclick: close });
    const sheet = el('div', { class: 'sheet' }, [
      el('h3', {}, r.song.title),
      el('p', { class: 'label-muted', style: 'margin-bottom:12px' }, 'Long-pressed — reassign this song\'s Last.fm track:'),
      el('button', { class: 'btn-secondary btn-block', onclick: () => { close(); reassign(r); } }, 'Set Last.fm track…'),
      el('button', { class: 'btn-secondary btn-block', onclick: async () => { close(); await apiPost('/api/lastfm/override/clear', { songId: r.song.id }); refetch(r); } }, 'Clear manual override'),
      el('button', { class: 'btn-ghost btn-block', onclick: close }, 'Cancel'),
    ]);
    document.body.append(backdrop, sheet);
    function close() { backdrop.remove(); sheet.remove(); }
  }

  async function reassign(r) {
    const url = prompt('Paste the Last.fm track URL:\n(e.g. https://www.last.fm/music/Artist/_/Track)');
    if (!url) return;
    try {
      await apiPost('/api/lastfm/override', { songId: r.song.id, url });
      toast('Reassigned');
      refetch(r);
    } catch (e) { toast(e.message, true); }
  }

  async function refetch(r) {
    r.resolved = false;
    r.el.querySelector('.plays').textContent = '…';
    await resolvePlaycount(r);
  }

  async function resolvePlaycount(r) {
    try {
      const res = await apiGet(`/api/lastfm/playcount?songId=${encodeURIComponent(r.song.id)}&artist=${encodeURIComponent(r.song.artist)}&title=${encodeURIComponent(r.song.title)}`);
      if (res.plays < 0) { if (r.el) r.el._countLabel.textContent = '—'; return; }
      r.playcount = res.plays;
      r.resolved = true;
      r.manual = res.manual;
      if (r.playcount > runningMax) { runningMax = r.playcount; rescaleAll(); }
      else rescale(r);
      updateLabel(r);
      if (sortByPlays) renderList();
    } catch { if (r.el) r.el._countLabel.textContent = '—'; }
  }

  function updateLabel(r) {
    if (!r.el) return;
    r.el._countLabel.textContent = `${r.playcount.toLocaleString()} plays`;
    r.el._countLabel.className = 'plays' + (r.manual ? ' manual' : '');
  }

  function rescale(r) {
    if (!r.el || !r.resolved) return;
    const width = runningMax <= 0 || r.playcount <= 0 ? 0 : Math.max(4, (r.playcount / runningMax) * 100);
    r.el._fill.style.width = width + '%';
  }
  function rescaleAll() { rows.forEach(rescale); }

  async function togglePlay(r, btn) {
    if (playingId === r.song.id) {
      if (isAudioPlaying()) { pausePreview(); btn.textContent = '▶'; }
      else { resumePreview(); btn.textContent = '⏸'; }
      return;
    }
    document.querySelectorAll('.play-mini').forEach(b => b.textContent = '▶');
    if (r.previewUrl === undefined) {
      try {
        const res = await apiGet(`/api/preview?artist=${encodeURIComponent(r.song.artist)}&title=${encodeURIComponent(r.song.title)}`);
        r.previewUrl = res.url;
      } catch { r.previewUrl = null; }
    }
    if (!r.previewUrl) { toast('No preview available'); return; }
    playPreview(r.previewUrl, () => { btn.textContent = '▶'; playingId = null; });
    btn.textContent = '⏸';
    playingId = r.song.id;
  }

  renderList();
  for (const r of rows) resolvePlaycount(r); // fire concurrently; each updates its row as it resolves
}
