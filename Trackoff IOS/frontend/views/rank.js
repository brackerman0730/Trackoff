import { el, header, nav, back, apiGet, playPreview, stopAudio, isAudioPlaying, pausePreview, resumePreview } from '../core.js';
import { getCurrentPlaylist } from '../state.js';
import { AdaptiveMergeSortRanker } from '../ranker.js';

export async function render(container) {
  const { name, songs } = getCurrentPlaylist();
  if (!songs || songs.length < 2) {
    container.appendChild(header('Rank', { onBack: () => nav('main') }));
    container.appendChild(el('p', {}, 'Need at least 2 songs to rank. Go back and pick a playlist.'));
    return;
  }

  const ranker = new AdaptiveMergeSortRanker(songs);
  let leftPreviewUrl = null, rightPreviewUrl = null;
  let playingSide = null; // 'left' | 'right' | null

  container.appendChild(header('Rank — ' + name, { onBack: () => { stopAudio(); nav('main'); } }));

  const progress = el('progress', { value: 0, max: 1 });
  const statsLabel = el('p', { class: 'label-muted', style: 'margin:8px 0 16px' });
  const cardsWrap = el('div', { class: 'compare-cards' });
  const btnGrid = el('div', { style: 'display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:10px' });
  const pickLeft = el('button', { class: 'btn-primary' }, '◀ Pick Left');
  const pickRight = el('button', { class: 'btn-primary' }, 'Pick Right ▶');
  const unknownBtn = el('button', { class: 'btn-secondary btn-block', style: 'margin-bottom:10px' }, "I don't know one of these");
  const tieBtn = el('button', { class: 'btn-secondary btn-block', style: 'margin-bottom:10px' }, "Skip (can't decide)");
  const undoBtn = el('button', { class: 'btn-ghost btn-block' }, '↶ Undo');
  btnGrid.append(pickLeft, pickRight);
  container.append(progress, statsLabel, cardsWrap, btnGrid, unknownBtn, tieBtn, undoBtn);

  pickLeft.onclick = () => answer('LEFT');
  pickRight.onclick = () => answer('RIGHT');
  tieBtn.onclick = () => answer('SKIP_TIE');
  undoBtn.onclick = () => { if (ranker.canUndo()) { ranker.undo(); refresh(); } };
  unknownBtn.onclick = () => {
    const req = ranker.nextRequest();
    if (!req) return;
    showUnknownSheet(req);
  };

  function showUnknownSheet(req) {
    const backdrop = el('div', { class: 'sheet-backdrop', onclick: () => close() });
    const sheet = el('div', { class: 'sheet' }, [
      el('h3', {}, 'Which song don\'t you know?'),
      el('button', { class: 'btn-secondary btn-block', onclick: () => { answer('REMOVE_LEFT'); close(); } }, 'Left: ' + req.left.title),
      el('button', { class: 'btn-secondary btn-block', onclick: () => { answer('REMOVE_RIGHT'); close(); } }, 'Right: ' + req.right.title),
      el('button', { class: 'btn-secondary btn-block', onclick: () => { answer('REMOVE_BOTH'); close(); } }, 'Both'),
      el('button', { class: 'btn-ghost btn-block', onclick: () => close() }, 'Cancel'),
    ]);
    document.body.append(backdrop, sheet);
    function close() { backdrop.remove(); sheet.remove(); }
  }

  function answer(choice) {
    if (!ranker.nextRequest()) return;
    ranker.submit(choice);
    refresh();
  }

  function refresh() {
    stopAudio(); playingSide = null; leftPreviewUrl = null; rightPreviewUrl = null;
    const req = ranker.nextRequest();
    if (!req) { showResults(); return; }

    const asked = ranker.comparisonsAsked;
    const est = ranker.estimatedTotalComparisons();
    progress.value = Math.min(1, asked / Math.max(1, est));
    statsLabel.textContent = `Comparison ${asked} · ${ranker.comparisonsSkippedByCache} auto-resolved · ~${est} max`;
    undoBtn.disabled = !ranker.canUndo();
    undoBtn.textContent = ranker.canUndo() ? `↶ Undo (${ranker.undoDepth()})` : '↶ Undo';

    cardsWrap.innerHTML = '';
    cardsWrap.append(buildCard(req.left, 'left'), buildCard(req.right, 'right'));
    resolvePreview(req.left, 'left');
    resolvePreview(req.right, 'right');
  }

  function buildCard(song, side) {
    const art = song.imageUrl
        ? el('img', { src: song.imageUrl })
        : el('div', { class: 'placeholder' }, '♪');
    const playBtn = el('button', { class: 'play-btn' }, '▶');
    playBtn.onclick = (e) => { e.stopPropagation(); toggleSide(side); };
    const card = el('div', { class: 'song-card' }, [
      art,
      el('div', { class: 'info' }, [
        el('div', { class: 'title' }, song.title),
        el('div', { class: 'artist' }, song.artist),
      ]),
      playBtn,
    ]);
    card.dataset.side = side;
    card.onclick = () => answer(side === 'left' ? 'LEFT' : 'RIGHT');
    card._playBtn = playBtn;
    return card;
  }

  async function resolvePreview(song, side) {
    try {
      const res = await apiGet(`/api/preview?artist=${encodeURIComponent(song.artist)}&title=${encodeURIComponent(song.title)}`);
      if (side === 'left') leftPreviewUrl = res.url; else rightPreviewUrl = res.url;
    } catch { /* no preview available — button just stays inert */ }
  }

  function toggleSide(side) {
    const url = side === 'left' ? leftPreviewUrl : rightPreviewUrl;
    if (!url) return;
    const card = [...cardsWrap.children].find(c => c.dataset.side === side);
    if (playingSide === side) {
      if (isAudioPlaying()) { pausePreview(); card._playBtn.textContent = '▶'; }
      else { resumePreview(); card._playBtn.textContent = '⏸'; }
      return;
    }
    [...cardsWrap.children].forEach(c => c._playBtn.textContent = '▶');
    playPreview(url, () => { card._playBtn.textContent = '▶'; playingSide = null; });
    card._playBtn.textContent = '⏸';
    playingSide = side;
  }

  function showResults() {
    stopAudio();
    container.innerHTML = '';
    container.appendChild(header('Results — ' + name, { onBack: () => nav('main') }));
    const ranked = ranker.finalRanking();
    const list = el('div', { class: 'menu-list' });
    ranked.forEach((s, idx) => {
      list.appendChild(el('div', { class: 'menu-item' }, [
        el('div', { class: 'icon', style: 'font-size:14px;color:var(--text-faint)' }, String(idx + 1)),
        el('div', {}, [
          el('div', { class: 'title' }, s.title),
          el('div', { class: 'sub' }, s.artist),
        ]),
      ]));
    });
    container.appendChild(list);
  }

  refresh();
}
