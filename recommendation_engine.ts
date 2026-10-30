/**
 * ============================================================================
 * FreeMyTV Recommendation Engine — On-Device Hybrid & Multi-Armed Bandit Model
 * ============================================================================
 * Implementação matemática e algorítmica on-device em TypeScript puro.
 * Latência esperada: < 0.5ms para lotes de até 500 candidatos.
 */

// ============================================================================
// 1. TIPOS, INTERFACES E ESTRUTURAS DE DADOS BÁSICAS
// ============================================================================

export const UniverseId = {
  SciFi: 0,    // Ficção Científica & Espaço (TMDB: 878, 10765)
  Fantasy: 1,  // Fantasia Épica & Aventura (TMDB: 14, 12)
  Action: 2,   // Ação & Super-Heróis (TMDB: 28, 10759)
  Suspense: 3, // Suspense, Crime & Terror (TMDB: 27, 80, 53, 9648)
  Comedy: 4,   // Comédia, Família & Romance (TMDB: 35, 10751, 10749, 10762)
  Drama: 5,    // Drama Profundo & História (TMDB: 18, 36, 10752, 10768, 99)
  Anime: 6,    // Anime & Animação (TMDB: 16 ou original_language === 'ja')
} as const;

export type UniverseId = typeof UniverseId[keyof typeof UniverseId];

/**
 * Tabela hash de busca estática O(1) para resolução de universos a partir de genre_id do TMDB.
 */
export const GENRE_TO_UNIVERSE_MAP: Readonly<Record<number, UniverseId>> = Object.freeze({
  // Sci-Fi
  878: UniverseId.SciFi,
  10765: UniverseId.SciFi,
  // Fantasy & Adventure
  14: UniverseId.Fantasy,
  12: UniverseId.Fantasy,
  // Action
  28: UniverseId.Action,
  10759: UniverseId.Action,
  // Suspense, Crime & Horror
  27: UniverseId.Suspense,
  80: UniverseId.Suspense,
  53: UniverseId.Suspense,
  9648: UniverseId.Suspense,
  // Comedy & Family
  35: UniverseId.Comedy,
  10751: UniverseId.Comedy,
  10749: UniverseId.Comedy,
  10762: UniverseId.Comedy,
  // Drama, History & War
  18: UniverseId.Drama,
  36: UniverseId.Drama,
  10752: UniverseId.Drama,
  10768: UniverseId.Drama,
  99: UniverseId.Drama,
  // Animation
  16: UniverseId.Anime,
});

export interface MediaItem {
  id: number;
  title: string;
  genre_ids: number[];
  original_language: string;
  vote_average: number;
  vote_count: number;
  franchise: string;
  keywords: string[];
}

export interface UserTensors {
  /** Pesos por Universo Temático: Clamped em [0.15, 6.0], Baseline neutro = 1.0 */
  W: Record<UniverseId, number>;
  /** Afinidade de Franquia acumulada: Clamped em [-20.0, 30.0] */
  FA: Record<string, number>;
  /** Blacklist de IDs de mídias rejeitadas */
  dislikedTitles: Set<number>;
  /** Blacklist de chaves de franquia rejeitadas */
  dislikedFranchises: Set<string>;
  /** Whitelist de IDs de mídias avaliadas positivamente */
  likedTitles: Set<number>;
}

export type InteractionType =
  | 'click'
  | 'completion'
  | 'rewatch'
  | 'like'
  | 'dislike'
  | 'abandon';

// ============================================================================
// 2. NORMALIZADOR DE FRANQUIA (REGEX STEMMING)
// ============================================================================

const REGEX_DIACRITICS = /[\u0300-\u036f]/g;
const REGEX_SUBTITLES = /\s*([:\-–—|•].*|\b(e\s+a\s+|e\s+o\s+|e\s+as\s+|e\s+os\s+|and\s+the\s+|parte|vol|volume|season|temporada|chapter|capítulo|saga|trilogia)\b.*)$/i;
const REGEX_NUMERICS = /\s+(\d+|[ivxlcdm]+)$/i;
const REGEX_CLEAN_PUNCT = /[^\w\s]/g;
const REGEX_MULTI_SPACE = /\s+/g;

/**
 * Normaliza o título isolando a raiz da franquia.
 */
export function normalizeFranchise(rawTitle: string): string {
  if (!rawTitle) return '';

  return rawTitle
    .normalize('NFD')
    .replace(REGEX_DIACRITICS, '')
    .toLowerCase()
    .replace(REGEX_SUBTITLES, '')
    .replace(REGEX_NUMERICS, '')
    .replace(REGEX_CLEAN_PUNCT, ' ')
    .replace(REGEX_MULTI_SPACE, ' ')
    .trim();
}

/**
 * Classifica um MediaItem em um dos 7 Universos Temáticos.
 */
export function resolveUniverseId(item: MediaItem): UniverseId {
  if (item.original_language === 'ja' && item.genre_ids.includes(16)) {
    return UniverseId.Anime;
  }
  if (item.genre_ids.includes(16)) {
    return UniverseId.Anime;
  }

  for (let i = 0; i < item.genre_ids.length; i++) {
    const universe = GENRE_TO_UNIVERSE_MAP[item.genre_ids[i]];
    if (universe !== undefined) {
      return universe;
    }
  }

  return (Math.abs(item.id) % 7) as UniverseId;
}

// ============================================================================
// 3. FUNÇÃO DE SCORING MULTI-DIMENSIONAL (COM MULTI-ARMED BANDIT)
// ============================================================================

const MIN_VOTE_AVERAGE = 6.5;
const MIN_VOTE_COUNT = 80;
const BONUS_LIKED = 25.0;
const EPSILON_BANDIT_MAX = 2.2;

export function scoreMediaItem(item: MediaItem, tensors: UserTensors): number {
  if (
    tensors.dislikedTitles.has(item.id) ||
    (item.franchise.length > 0 && tensors.dislikedFranchises.has(item.franchise))
  ) {
    return -Infinity;
  }

  if (item.vote_average < MIN_VOTE_AVERAGE || item.vote_count < MIN_VOTE_COUNT) {
    return -Infinity;
  }

  const universe = resolveUniverseId(item);
  const worldWeight = tensors.W[universe] ?? 1.0;
  const franchiseWeight = item.franchise ? (tensors.FA[item.franchise] ?? 0.0) : 0.0;
  const bLiked = tensors.likedTitles.has(item.id) ? BONUS_LIKED : 0.0;
  const epsilonBandit = Math.random() * EPSILON_BANDIT_MAX;

  return (item.vote_average * worldWeight) + franchiseWeight + bLiked + epsilonBandit;
}

// ============================================================================
// 4. CICLO DE APRENDIZADO POR REFORÇO (FEEDBACK LOOPS & TASTE DRIFT)
// ============================================================================

const W_MIN = 0.15;
const W_MAX = 6.0;
const FA_MIN = -20.0;
const FA_MAX = 30.0;
const DECAY_RATE = 0.95;

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

export function createDefaultUserTensors(): UserTensors {
  return {
    W: {
      [UniverseId.SciFi]: 1.0,
      [UniverseId.Fantasy]: 1.0,
      [UniverseId.Action]: 1.0,
      [UniverseId.Suspense]: 1.0,
      [UniverseId.Comedy]: 1.0,
      [UniverseId.Drama]: 1.0,
      [UniverseId.Anime]: 1.0,
    },
    FA: Object.create(null),
    dislikedTitles: new Set<number>(),
    dislikedFranchises: new Set<string>(),
    likedTitles: new Set<number>(),
  };
}

export function handleInteractionEvent(
  event: InteractionType,
  item: MediaItem,
  tensors: UserTensors,
  metadata?: { timesWatched?: number }
): void {
  const universe = resolveUniverseId(item);
  const franchise = item.franchise;

  let deltaW = 0.0;
  let deltaFA = 0.0;

  switch (event) {
    case 'click':
      deltaW = 0.35;
      deltaFA = 0.80;
      break;

    case 'completion':
      deltaW = 0.40;
      deltaFA = 0.60;
      break;

    case 'rewatch': {
      const timesWatched = Math.max(1, metadata?.timesWatched ?? 1);
      deltaW = 0.20 * timesWatched;
      deltaFA = 0.40 * timesWatched;
      break;
    }

    case 'like':
      deltaW = 1.50;
      deltaFA = 3.50;
      tensors.likedTitles.add(item.id);
      break;

    case 'dislike':
      deltaW = -1.20;
      deltaFA = -15.00;
      tensors.dislikedTitles.add(item.id);
      if (franchise) {
        tensors.dislikedFranchises.add(franchise);
      }
      break;

    case 'abandon':
      deltaW = -0.30;
      deltaFA = -0.50;
      break;

    default:
      return;
  }

  tensors.W[universe] = clamp((tensors.W[universe] ?? 1.0) + deltaW, W_MIN, W_MAX);

  if (franchise) {
    tensors.FA[franchise] = clamp((tensors.FA[franchise] ?? 0.0) + deltaFA, FA_MIN, FA_MAX);
  }
}

export function applyDailyHarmonicDecay(tensors: UserTensors): void {
  const universeKeys = [
    UniverseId.SciFi,
    UniverseId.Fantasy,
    UniverseId.Action,
    UniverseId.Suspense,
    UniverseId.Comedy,
    UniverseId.Drama,
    UniverseId.Anime,
  ] as const;

  for (let i = 0; i < universeKeys.length; i++) {
    const u = universeKeys[i];
    const currentW = tensors.W[u] ?? 1.0;
    tensors.W[u] = clamp(1.0 + (currentW - 1.0) * DECAY_RATE, W_MIN, W_MAX);
  }
}

// ============================================================================
// 5. ALGORITMO 'mixWorlds()' (INTERCALAÇÃO ROUND-ROBIN COM INVARIANTE)
// ============================================================================

export interface ScoredMediaItem {
  item: MediaItem;
  score: number;
  universe: UniverseId;
}

const MAX_DISPLAY_CARDS = 9;

export function mixWorlds(
  candidates: MediaItem[],
  tensors: UserTensors
): MediaItem[] {
  if (candidates.length === 0) return [];

  const scoredItems: ScoredMediaItem[] = [];
  const seenFranchises = new Set<string>();

  for (let i = 0; i < candidates.length; i++) {
    const item = candidates[i];
    const score = scoreMediaItem(item, tensors);

    if (score === -Infinity) continue;

    if (item.franchise) {
      if (seenFranchises.has(item.franchise)) continue;
      seenFranchises.add(item.franchise);
    }

    scoredItems.push({
      item,
      score,
      universe: resolveUniverseId(item),
    });
  }

  scoredItems.sort((a, b) => b.score - a.score);

  const buckets: MediaItem[][] = Array.from({ length: 7 }, () => []);
  for (let i = 0; i < scoredItems.length; i++) {
    buckets[scoredItems[i].universe].push(scoredItems[i].item);
  }

  const result: MediaItem[] = [];
  let lastUniverse = -1;
  const targetCount = Math.min(MAX_DISPLAY_CARDS, scoredItems.length);

  while (result.length < targetCount) {
    let itemAddedInCycle = false;

    for (let u = 0; u < 7; u++) {
      if (u === lastUniverse) continue;

      const bucket = buckets[u];
      if (bucket.length > 0) {
        const item = bucket.shift()!;
        result.push(item);
        lastUniverse = u;
        itemAddedInCycle = true;

        if (result.length >= targetCount) break;
      }
    }

    if (!itemAddedInCycle) {
      let highestBucketIndex = -1;

      for (let u = 0; u < 7; u++) {
        if (buckets[u].length > 0) {
          highestBucketIndex = u;
          break;
        }
      }

      if (highestBucketIndex !== -1) {
        result.push(buckets[highestBucketIndex].shift()!);
      } else {
        break;
      }
    }
  }

  return result;
}
