// Shared "currently loaded playlist" state — set by Library/CSV-import,
// read by Rank/Swipe/Tier List/Last.fm Manager. A module-level singleton
// is enough here (single-user, single-tab app) and avoids stuffing a
// whole track list into the URL.

let current = { name: '', playlistId: null, songs: [] };

export function setCurrentPlaylist(name, songs, playlistId = null) {
  current = { name, songs, playlistId };
}

export function getCurrentPlaylist() {
  return current;
}
