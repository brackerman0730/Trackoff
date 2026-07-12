import { el, header, nav, toast } from '../core.js';
import { showDestinationChooser } from '../chooser.js';

export async function render(container) {
  container.appendChild(header('Import CSV', { onBack: () => nav('main') }));
  container.appendChild(el('p', { class: 'label-muted', style: 'margin-bottom:16px' },
      'Pick a CSV export (Exportify, TuneMyMusic, or a Spotify library export).'));

  const fileInput = el('input', { type: 'file', accept: '.csv,text/csv' });
  container.appendChild(fileInput);

  fileInput.onchange = async () => {
    const file = fileInput.files[0];
    if (!file) return;
    const text = await file.text();
    try {
      const res = await fetch('/api/csv/parse', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: text });
      const songs = await res.json();
      if (!res.ok) throw new Error(songs.error || 'Parse failed');
      if (songs.length === 0) { toast('No songs found in that file', true); return; }
      const name = file.name.replace(/\.csv$/i, '');
      showDestinationChooser(name, songs, null);
    } catch (e) {
      toast(e.message, true);
    }
  };
}
