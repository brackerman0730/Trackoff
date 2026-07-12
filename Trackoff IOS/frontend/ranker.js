// Adaptive bottom-up merge sort, ported line-for-line from the desktop
// app's AdaptiveMergeSortRanker.java + TransitivityCache.java. Produces
// a descending ranking (index 0 = most preferred) from pairwise choices,
// skipping any comparison whose answer is already implied by transitivity.

class TransitivityCache {
  constructor() { this.preferredOver = new Map(); }

  recordPreference(winner, loser) {
    if (!this.preferredOver.has(winner)) this.preferredOver.set(winner, new Set());
    this.preferredOver.get(winner).add(loser);
  }

  isPreferredOver(a, b) { return this.reachable(a, b, new Set()); }

  reachable(from, target, visited) {
    if (visited.has(from)) return false;
    visited.add(from);
    const direct = this.preferredOver.get(from);
    if (!direct) return false;
    if (direct.has(target)) return true;
    for (const next of direct) if (this.reachable(next, target, visited)) return true;
    return false;
  }

  copy() {
    const c = new TransitivityCache();
    for (const [k, v] of this.preferredOver) c.preferredOver.set(k, new Set(v));
    return c;
  }
}

export class AdaptiveMergeSortRanker {
  constructor(songs) {
    this.songs = songs.slice();
    this.idToIndex = new Map(this.songs.map((s, k) => [s.id, k]));
    this.order = this.songs.map((_, k) => k);

    this.width = 1;
    this.leftStart = 0;
    this.mid = 0; this.rightEnd = 0; this.i = 0; this.j = 0;
    this.buffer = null; this.bufferSize = 0;
    this.done = this.songs.length <= 1;

    this.comparisonsAsked = 0;
    this.comparisonsSkippedByCache = 0;
    this.cache = new TransitivityCache();
    this.pending = null;
    this.removedIds = new Set();
    this.undoStack = [];

    this.advanceUntilQuestion();
  }

  nextRequest() { return this.done ? null : this.pending; }

  submit(choice) {
    if (!this.pending) throw new Error('No pending comparison.');
    this.pushUndoFrame();
    const { left, right } = this.pending;
    this.pending = null;

    switch (choice) {
      case 'LEFT':
        this.cache.recordPreference(left.id, right.id);
        this.applyMergeStep(true);
        break;
      case 'RIGHT':
        this.cache.recordPreference(right.id, left.id);
        this.applyMergeStep(false);
        break;
      case 'SKIP_TIE':
        this.applyMergeStep((left.popularity || 0) >= (right.popularity || 0));
        break;
      case 'REMOVE_LEFT':
        this.removedIds.add(left.id);
        this.applyMergeStep(false);
        break;
      case 'REMOVE_RIGHT':
        this.removedIds.add(right.id);
        this.applyMergeStep(true);
        break;
      case 'REMOVE_BOTH':
        this.removedIds.add(left.id); this.removedIds.add(right.id);
        this.applyMergeStep(true);
        break;
    }
    this.advanceUntilQuestion();
  }

  canUndo() { return this.undoStack.length > 0; }
  undoDepth() { return this.undoStack.length; }

  undo() {
    if (this.undoStack.length === 0) return;
    const f = this.undoStack.pop();
    this.order = f.order.slice();
    this.width = f.width; this.leftStart = f.leftStart; this.mid = f.mid;
    this.rightEnd = f.rightEnd; this.i = f.i; this.j = f.j;
    this.buffer = f.buffer ? f.buffer.slice() : null;
    this.bufferSize = f.bufferSize;
    this.done = f.done;
    this.comparisonsSkippedByCache = f.comparisonsSkippedByCache;
    this.comparisonsAsked = f.comparisonsAsked - 1;
    this.cache = f.cache;
    this.removedIds = new Set(f.removedIds);
    this.pending = null;
    this.advanceUntilQuestion();
  }

  pushUndoFrame() {
    this.undoStack.push({
      order: this.order.slice(),
      width: this.width, leftStart: this.leftStart, mid: this.mid,
      rightEnd: this.rightEnd, i: this.i, j: this.j,
      buffer: this.buffer ? this.buffer.slice() : null,
      bufferSize: this.bufferSize, done: this.done,
      comparisonsAsked: this.comparisonsAsked,
      comparisonsSkippedByCache: this.comparisonsSkippedByCache,
      cache: this.cache.copy(),
      removedIds: new Set(this.removedIds),
    });
    if (this.undoStack.length > 200) this.undoStack.shift();
  }

  isFinished() { return this.done; }

  finalRanking() {
    return this.order.map(idx => this.songs[idx]).filter(s => !this.removedIds.has(s.id));
  }

  estimatedTotalComparisons() {
    const n = this.songs.length;
    if (n <= 1) return 0;
    const logN = Math.ceil(Math.log2(n));
    return n * logN;
  }

  advanceUntilQuestion() {
    while (!this.done && !this.pending) {
      if (this.buffer === null) {
        if (this.leftStart >= this.songs.length - this.width) {
          this.width *= 2;
          this.leftStart = 0;
          if (this.width >= this.songs.length) { this.done = true; return; }
          continue;
        }
        this.mid = Math.min(this.leftStart + this.width, this.songs.length);
        this.rightEnd = Math.min(this.leftStart + 2 * this.width, this.songs.length);
        this.i = this.leftStart;
        this.j = this.mid;
        this.buffer = new Array(this.rightEnd - this.leftStart);
        this.bufferSize = 0;
      }

      if (this.i >= this.mid) { this.drainFromRight(); continue; }
      if (this.j >= this.rightEnd) { this.drainFromLeft(); continue; }

      const leftSong = this.songs[this.order[this.i]];
      const rightSong = this.songs[this.order[this.j]];
      if (this.cache.isPreferredOver(leftSong.id, rightSong.id)) {
        this.comparisonsSkippedByCache++;
        this.applyMergeStep(true);
        continue;
      }
      if (this.cache.isPreferredOver(rightSong.id, leftSong.id)) {
        this.comparisonsSkippedByCache++;
        this.applyMergeStep(false);
        continue;
      }

      this.pending = { left: leftSong, right: rightSong };
      this.comparisonsAsked++;
      return;
    }
  }

  applyMergeStep(leftWins) {
    if (leftWins) this.buffer[this.bufferSize++] = this.order[this.i++];
    else this.buffer[this.bufferSize++] = this.order[this.j++];
    if (this.i >= this.mid && this.j >= this.rightEnd) this.finishMerge();
  }

  drainFromLeft() { while (this.i < this.mid) this.buffer[this.bufferSize++] = this.order[this.i++]; this.finishMerge(); }
  drainFromRight() { while (this.j < this.rightEnd) this.buffer[this.bufferSize++] = this.order[this.j++]; this.finishMerge(); }

  finishMerge() {
    for (let k = 0; k < this.buffer.length; k++) this.order[this.leftStart + k] = this.buffer[k];
    this.buffer = null;
    this.leftStart += 2 * this.width;
  }
}
