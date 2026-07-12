import { registerRoute, startRouter, toast } from './core.js';
import * as mainView from './views/main.js';
import * as connect from './views/connect.js';
import * as library from './views/library.js';
import * as csvImport from './views/csv-import.js';
import * as rank from './views/rank.js';
import * as swipe from './views/swipe.js';
import * as tierlist from './views/tierlist.js';
import * as lastfmManager from './views/lastfm-manager.js';

registerRoute('main', mainView.render);
registerRoute('connect-spotify', connect.renderSpotify);
registerRoute('connect-lastfm', connect.renderLastFm);
registerRoute('library', library.render);
registerRoute('csv-import', csvImport.render);
registerRoute('rank', rank.render);
registerRoute('swipe', swipe.render);
registerRoute('tierlist', tierlist.render);
registerRoute('lastfm-manager', lastfmManager.render);

startRouter();

// Surface the OAuth-callback redirect flags from Main.java's /api/spotify/callback.
const params = new URLSearchParams(window.location.search);
if (params.get('spotifyConnected')) { toast('Spotify connected!'); history.replaceState(null, '', window.location.pathname + window.location.hash); }
if (params.get('spotifyError')) { toast('Spotify error: ' + params.get('spotifyError'), true); history.replaceState(null, '', window.location.pathname + window.location.hash); }

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}
