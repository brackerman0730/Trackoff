import { el, header, nav, playPreview, stopAudio, isAudioPlaying, pausePreview, resumePreview, apiGet, toast } from '../core.js';
import { getCurrentPlaylist } from '../state.js';

const DEFAULT_TIERS = [
  ['S', '#ff7f7f'], ['A', '#ffbf7f'], ['B', '#ffdf7f'],
  ['C', '#ffff7f'], ['D', '#bfff7f'], ['F', '#7fbfff'],
];

export async function render(container) {
  const { name, songs } = getCurrentPlaylist();
  if (!songs || songs.length < 1) {
    container.appendChild(header('Tier List', { onBack: () => nav('main') }));
    container.appendChild(el('p', {}, 'No songs. Go back and pick a playlist.'));
    return;
  }

  container.appendChild(header('Tier List', { onBack: () => { stopAudio(); nav('main'); } }));

  const toolbar = el('div', { class: 'tier-toolbar' });
  const addTierBtn = el('button', { class: 'btn-ghost' }, '+ Add Tier');
  toolbar.appendChild(addTierBtn);
  container.appendChild(toolbar);

  const columnEl = el('div', {});
  container.appendChild(columnEl);

  let nextUid = 0;
  const rows = [
    { uid: nextUid++, name: 'Unranked', color: '#3a3a3a', isPool: true, songs: songs.slice() },
    ...DEFAULT_TIERS.map(([n, c]) => ({ uid: nextUid++, name: n, color: c, isPool: false, songs: [] })),
  ];

  let playingId = null, playingUrlCache = {};

  addTierBtn.onclick = () => {
    const n = prompt('Name for the new tier:', 'New Tier');
    if (!n || !n.trim()) return;
    const color = DEFAULT_TIERS[(rows.length - 1) % DEFAULT_TIERS.length][1];
    rows.push({ uid: nextUid++, name: n.trim(), color, isPool: false, songs: [] });
    renderRows();
  };

  function findRowBySong(songId) {
    return rows.find(r => r.songs.some(s => s.id === songId));
  }

  function moveSong(songId, targetUid, insertIndex) {
    const fromRow = findRowBySong(songId);
    if (!fromRow) return;
    const idx = fromRow.songs.findIndex(s => s.id === songId);
    const [song] = fromRow.songs.splice(idx, 1);
    const toRow = rows.find(r => r.uid === targetUid);
    if (!toRow) { fromRow.songs.splice(idx, 0, song); return; }
    const clampedIndex = insertIndex === undefined ? toRow.songs.length : Math.max(0, Math.min(insertIndex, toRow.songs.length));
    toRow.songs.splice(clampedIndex, 0, song);
  }

  function deleteTier(uid) {
    const row = rows.find(r => r.uid === uid);
    if (!row || row.isPool) return;
    if (!confirm(`Delete tier "${row.name}"? ${row.songs.length} song(s) move back to Unranked.`)) return;
    rows[0].songs.push(...row.songs);
    const i = rows.findIndex(r => r.uid === uid);
    rows.splice(i, 1);
    renderRows();
  }

  function renameTier(uid) {
    const row = rows.find(r => r.uid === uid);
    if (!row) return;
    const n = prompt('New name for this tier:', row.name);
    if (n && n.trim()) { row.name = n.trim(); renderRows(); }
  }

  function reorderTier(draggedUid, targetUid, before) {
    const draggedIdx = rows.findIndex(r => r.uid === draggedUid);
    if (draggedIdx < 0) return;
    const [dragged] = rows.splice(draggedIdx, 1);
    let targetIdx = rows.findIndex(r => r.uid === targetUid);
    if (targetIdx < 0) targetIdx = rows.length;
    let insertAt = before ? targetIdx : targetIdx + 1;
    insertAt = Math.max(insertAt, 1); // never before the pool
    rows.splice(insertAt, 0, dragged);
    renderRows();
  }

  function renderRows() {
    columnEl.innerHTML = '';
    for (const row of rows) columnEl.appendChild(buildRow(row));
  }

  function buildRow(row) {
    const content = el('div', { class: 'tier-content' });
    content.dataset.uid = row.uid;
    for (const s of row.songs) content.appendChild(buildTile(s));

    const labelCol = el('div', {
      class: 'tier-label-col' + (row.isPool ? '' : ''),
      style: `background:${row.color}`,
    }, [row.name]);
    labelCol.dataset.uid = row.uid;

    if (!row.isPool) {
      labelCol.onclick = () => renameTier(row.uid);
      const del = el('div', { class: 'del-tier' }, '✕');
      del.onclick = (e) => { e.stopPropagation(); deleteTier(row.uid); };
      labelCol.appendChild(del);
      attachTierDrag(labelCol, row.uid);
    }

    const rowEl = el('div', { class: 'tier-row' + (row.isPool ? ' pool-row' : '') }, [labelCol, content]);
    return rowEl;
  }

  function buildTile(song) {
    const art = song.imageUrl ? el('img', { src: song.imageUrl }) : el('div', { class: 'placeholder' }, '♪');
    const tile = el('div', { class: 'tier-tile' }, [art, el('div', { class: 'tile-title' }, song.title)]);
    tile.dataset.songId = song.id;
    attachTileDrag(tile, song);
    return tile;
  }

  // ---- Touch drag: song tiles between tiers ----
  function attachTileDrag(tile, song) {
    let ghost = null, dragging = false, longPressTimer = null, tapStart = 0;

    tile.addEventListener('pointerdown', e => {
      tapStart = Date.now();
      longPressTimer = setTimeout(() => startDrag(e), 140);
    });
    tile.addEventListener('pointerup', e => {
      clearTimeout(longPressTimer);
      if (!dragging) {
        if (Date.now() - tapStart < 250) togglePlay(song, tile);
      } else {
        endDrag(e);
      }
    });
    tile.addEventListener('pointercancel', () => { clearTimeout(longPressTimer); if (dragging) cleanupGhost(); });
    tile.addEventListener('pointermove', e => {
      if (dragging) moveGhost(e);
    });

    function startDrag(e) {
      dragging = true;
      tile.classList.add('dragging');
      const rect = tile.getBoundingClientRect();
      ghost = tile.cloneNode(true);
      ghost.style.cssText = `position:fixed;left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;height:${rect.height}px;z-index:999;pointer-events:none;opacity:0.9;`;
      document.body.appendChild(ghost);
      moveGhost(e);
    }
    function moveGhost(e) {
      if (!ghost) return;
      ghost.style.left = (e.clientX - 30) + 'px';
      ghost.style.top = (e.clientY - 30) + 'px';
      document.querySelectorAll('.tier-content.drop-target').forEach(c => c.classList.remove('drop-target'));
      const under = document.elementFromPoint(e.clientX, e.clientY);
      const dropZone = under && under.closest('.tier-content');
      if (dropZone) dropZone.classList.add('drop-target');
    }
    function endDrag(e) {
      cleanupGhost();
      const under = document.elementFromPoint(e.clientX, e.clientY);
      const dropZone = under && under.closest('.tier-content');
      if (dropZone) {
        moveSong(song.id, Number(dropZone.dataset.uid));
        renderRows();
      }
    }
    function cleanupGhost() {
      dragging = false;
      tile.classList.remove('dragging');
      document.querySelectorAll('.tier-content.drop-target').forEach(c => c.classList.remove('drop-target'));
      if (ghost) { ghost.remove(); ghost = null; }
    }
  }

  // ---- Touch drag: whole tier rows, to reorder ----
  function attachTierDrag(labelCol, uid) {
    let dragging = false, startY = 0, longPressTimer = null;
    labelCol.addEventListener('pointerdown', e => {
      startY = e.clientY;
      longPressTimer = setTimeout(() => { dragging = true; labelCol.style.opacity = '0.5'; }, 250);
    });
    labelCol.addEventListener('pointermove', e => {
      if (!dragging) return;
      document.querySelectorAll('.tier-row').forEach(r => r.style.outline = '');
      const under = document.elementFromPoint(e.clientX, e.clientY);
      const rowEl = under && under.closest('.tier-row');
      if (rowEl) rowEl.style.outline = '2px solid var(--green)';
    });
    labelCol.addEventListener('pointerup', e => {
      clearTimeout(longPressTimer);
      if (!dragging) return;
      dragging = false;
      labelCol.style.opacity = '1';
      document.querySelectorAll('.tier-row').forEach(r => r.style.outline = '');
      const under = document.elementFromPoint(e.clientX, e.clientY);
      const rowEl = under && under.closest('.tier-row');
      if (rowEl) {
        const targetLabel = rowEl.querySelector('.tier-label-col');
        const targetUid = Number(targetLabel.dataset.uid);
        if (targetUid !== uid) {
          const rect = rowEl.getBoundingClientRect();
          const before = e.clientY < rect.top + rect.height / 2;
          reorderTier(uid, targetUid, before);
        }
      }
    });
    labelCol.addEventListener('pointercancel', () => { clearTimeout(longPressTimer); dragging = false; labelCol.style.opacity = '1'; });
  }

  async function togglePlay(song, tile) {
    if (playingId === song.id) {
      if (isAudioPlaying()) pausePreview(); else resumePreview();
      return;
    }
    playingId = song.id;
    let url = playingUrlCache[song.id];
    if (url === undefined) {
      try {
        const res = await apiGet(`/api/preview?artist=${encodeURIComponent(song.artist)}&title=${encodeURIComponent(song.title)}`);
        url = res.url;
      } catch { url = null; }
      playingUrlCache[song.id] = url;
    }
    if (!url) { toast('No preview available'); return; }
    playPreview(url, () => { playingId = null; });
  }

  renderRows();
}
