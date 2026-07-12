import { el, header, nav, apiGet, playPreview, stopAudio } from '../core.js';
import { getCurrentPlaylist } from '../state.js';

const SWIPE_THRESHOLD = 100;

export async function render(container) {
  const { name, songs } = getCurrentPlaylist();
  if (!songs || songs.length < 1) {
    container.appendChild(header('Swipe', { onBack: () => nav('main') }));
    container.appendChild(el('p', {}, 'No songs to swipe. Go back and pick a playlist.'));
    return;
  }

  let index = 0;
  const kept = [], deleted = [];
  const history = [];
  let preloadedUrl = null, preloadedSongId = null;

  container.appendChild(header('Swipe — ' + name, { onBack: () => { stopAudio(); nav('main'); } }));
  const progressLabel = el('p', { class: 'label-muted' });
  container.appendChild(progressLabel);

  const stage = el('div', { class: 'swipe-stage' });
  container.appendChild(stage);

  const actions = el('div', { class: 'swipe-actions' });
  const deleteBtn = el('button', { class: 'btn-danger' }, '✕');
  const undoBtn = el('button', { class: 'btn-ghost', style: 'border-radius:50%;width:58px;height:58px' }, '↶');
  const keepBtn = el('button', { class: 'btn-primary' }, '♥');
  actions.append(deleteBtn, undoBtn, keepBtn);
  container.appendChild(actions);
  container.appendChild(el('p', { class: 'label-muted', style: 'text-align:center;margin-top:10px' }, 'Drag the card, or tap ✕ / ♥'));

  deleteBtn.onclick = () => commitSwipe(-1);
  keepBtn.onclick = () => commitSwipe(1);
  undoBtn.onclick = () => undo();

  let card;

  function loadCurrent() {
    if (index >= songs.length) { showSummary(); return; }
    const song = songs[index];
    progressLabel.textContent = `${index + 1} / ${songs.length}`;
    stage.innerHTML = '';
    card = buildCard(song);
    stage.appendChild(card);
    startPreview(song);
  }

  function buildCard(song) {
    const art = song.imageUrl ? el('img', { src: song.imageUrl }) : el('div', { class: 'placeholder' }, '♪');
    const c = el('div', { class: 'swipe-card' }, [
      el('div', { class: 'swipe-stamp keep' }, 'KEEP'),
      el('div', { class: 'swipe-stamp nope' }, 'NOPE'),
      art,
      el('div', { class: 'title' }, song.title),
      el('div', { class: 'artist' }, song.artist),
    ]);
    attachDrag(c);
    return c;
  }

  function attachDrag(c) {
    let startX = 0, startY = 0, dx = 0, dragging = false;
    const keepStamp = c.children[0], nopeStamp = c.children[1];

    c.addEventListener('pointerdown', e => {
      dragging = true; startX = e.clientX; startY = e.clientY;
      c.setPointerCapture(e.pointerId);
    });
    c.addEventListener('pointermove', e => {
      if (!dragging) return;
      dx = e.clientX - startX;
      const dy = e.clientY - startY;
      c.style.transform = `translate(${dx}px, ${dy * 0.3}px) rotate(${dx * 0.06}deg)`;
      const t = Math.min(1, Math.abs(dx) / SWIPE_THRESHOLD);
      if (dx > 0) { keepStamp.style.opacity = t; nopeStamp.style.opacity = 0; }
      else { nopeStamp.style.opacity = t; keepStamp.style.opacity = 0; }
    });
    const end = () => {
      if (!dragging) return;
      dragging = false;
      if (Math.abs(dx) >= SWIPE_THRESHOLD) commitSwipe(dx > 0 ? 1 : -1);
      else snapBack(c, keepStamp, nopeStamp);
      dx = 0;
    };
    c.addEventListener('pointerup', end);
    c.addEventListener('pointercancel', end);
  }

  function snapBack(c, keepStamp, nopeStamp) {
    c.style.transition = 'transform 0.2s';
    c.style.transform = 'translate(0,0) rotate(0)';
    keepStamp.style.opacity = 0; nopeStamp.style.opacity = 0;
    setTimeout(() => { c.style.transition = ''; }, 200);
  }

  function commitSwipe(direction) {
    if (index >= songs.length) return;
    const song = songs[index];
    (direction > 0 ? kept : deleted).push(song);
    history.push({ song, kept: direction > 0 });

    if (card) {
      card.style.transition = 'transform 0.25s, opacity 0.25s';
      card.style.transform = `translate(${direction * 500}px, -40px) rotate(${direction * 20}deg)`;
      card.style.opacity = '0';
    }
    setTimeout(() => { index++; loadCurrent(); }, 200);
  }

  function undo() {
    if (history.length === 0) return;
    const last = history.pop();
    const pile = last.kept ? kept : deleted;
    const i = pile.lastIndexOf(last.song);
    if (i >= 0) pile.splice(i, 1);
    index--;
    loadCurrent();
  }

  function startPreview(song) {
    stopAudio();
    if (preloadedSongId === song.id && preloadedUrl) {
      playPreview(preloadedUrl);
    } else {
      apiGet(`/api/preview?artist=${encodeURIComponent(song.artist)}&title=${encodeURIComponent(song.title)}`)
          .then(res => { if (songs[index] === song && res.url) playPreview(res.url); })
          .catch(() => {});
    }
    preloadNext();
  }

  function preloadNext() {
    const next = songs[index + 1];
    if (!next) return;
    preloadedSongId = next.id;
    apiGet(`/api/preview?artist=${encodeURIComponent(next.artist)}&title=${encodeURIComponent(next.title)}`)
        .then(res => { if (preloadedSongId === next.id) preloadedUrl = res.url; })
        .catch(() => {});
  }

  function showSummary() {
    stopAudio();
    container.innerHTML = '';
    container.appendChild(header('Swipe complete', { onBack: () => nav('main') }));
    container.appendChild(el('h3', { style: 'margin-bottom:8px' }, `Kept (${kept.length})`));
    container.appendChild(listOf(kept));
    container.appendChild(el('h3', { style: 'margin:16px 0 8px' }, `Deleted (${deleted.length})`));
    container.appendChild(listOf(deleted));
  }

  function listOf(arr) {
    const list = el('div', { class: 'menu-list' });
    for (const s of arr) {
      list.appendChild(el('div', { class: 'menu-item' }, [
        el('div', {}, [el('div', { class: 'title' }, s.title), el('div', { class: 'sub' }, s.artist)]),
      ]));
    }
    return list;
  }

  loadCurrent();
}
