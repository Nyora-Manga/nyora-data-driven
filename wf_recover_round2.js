export const meta = {
  name: 'source-recovery-round2',
  description: 'Find current/alternative domains for dead manga sources + research minimal-load Cloudflare bypass',
  phases: [
    { title: 'Recover', detail: 'round-2: junk-migrated + uncertain dead domains' },
  ],
}

// Embedded recover work-list: [[domain, name, engine, probeStatus], ...]
const recover = [["www.mangahub.link", "MangaHub.link", "zeistmanga", "MIGRATED_JUNK"], ["www.manga-soul.com", "MangaSoul", "zeistmanga", "MIGRATED_JUNK"], ["gistamishousefansub.blogspot.com", "GistamisHouseFansub", "zeistmanga", "MIGRATED_JUNK"], ["kaijuno8.fr", "KaijuNo8", "onemanga", "MIGRATED_JUNK"], ["scyllacomics.xyz", "ScyllaComics", "fuzzydoodle", "MIGRATED_JUNK"], ["adultwebtoon.com", "AdultWebtoon", "madara", "MIGRATED_JUNK"], ["asurascans.us", "AsuraScansGg", "madara", "MIGRATED_JUNK"], ["freemanga.me", "FreeManga", "madara", "MIGRATED_JUNK"], ["hentaimanga.me", "HentaiManga", "madara", "MIGRATED_JUNK"], ["hentaiwebtoon.com", "HentaiWebtoon", "madara", "MIGRATED_JUNK"], ["likemanga.in", "LikeManga.in", "madara", "MIGRATED_JUNK"], ["mangahentai.me", "MangaHentai", "madara", "MIGRATED_JUNK"], ["manhwahentai.me", "ManhwaHentai", "madara", "MIGRATED_JUNK"], ["manhwanew.com", "ManhwaNew", "madara", "MIGRATED_JUNK"], ["manhwaraw.com", "ManhwaRaw.com", "madara", "MIGRATED_JUNK"], ["manycomic.com", "ManyComic", "madara", "MIGRATED_JUNK"], ["manytoon.me", "ManyToon.me", "madara", "MIGRATED_JUNK"], ["neatmangas.com", "NeatManga", "madara", "MIGRATED_JUNK"], ["newmanhua.com", "NewManhua", "madara", "MIGRATED_JUNK"], ["platinumscans.com", "PlatinumScans", "madara", "MIGRATED_JUNK"], ["porncomix.online", "PornComix.online", "madara", "MIGRATED_JUNK"], ["portalyaoi.com", "PortalYaoi", "madara", "MIGRATED_JUNK"], ["yuri.live", "YuriLive", "madara", "MIGRATED_JUNK"], ["armoniscans.net", "ArmoniScans", "madara", "MIGRATED_JUNK"], ["guncelmanga.net", "GuncelManga", "madara", "MIGRATED_JUNK"], ["daprob.com", "Daprob", "madara", "MIGRATED_JUNK"], ["02.lumosgg.com", "LumosKomik", "madara", "MIGRATED_JUNK"], ["mangamammy.ru", "MangaMammy", "madara", "MIGRATED_JUNK"], ["kenscans.com", "KenScans", "keyoapp", "MIGRATED_JUNK"], ["scarmanga.com", "ScarManga", "mangareader", "MIGRATED_JUNK"], ["ehentaimanga.com", "EHentaiManga", "mangareader", "MIGRATED_JUNK"], ["nightsup.net", "NightScans", "mangareader", "MIGRATED_JUNK"], ["adumanga.com", "AduManga", "mangareader", "MIGRATED_JUNK"], ["asemifansub.com", "AsemiFansub", "mangareader", "MIGRATED_JUNK"], ["atemporal.cloud", "atemporal", "unknown", "UNCERTAIN"], ["fairydream.com.br", "fairydream", "unknown", "UNCERTAIN"], ["limboscan.com.br", "limboscan", "unknown", "UNCERTAIN"], ["pirulitorosa.site", "pirulitorosa", "unknown", "UNCERTAIN"], ["pussy.sussytoons.com", "pussy", "unknown", "UNCERTAIN"], ["hentaivn.party", "hentaivn", "unknown", "UNCERTAIN"], ["raikiscan.com", "raikiscan", "unknown", "UNCERTAIN"], ["doctruyen3qui15.pro", "doctruyen3qui15", "unknown", "UNCERTAIN"], ["nettruyen1905.com", "nettruyen1905", "unknown", "UNCERTAIN"], ["mangapure.net", "mangapure", "unknown", "UNCERTAIN"], ["mangaus.xyz", "mangaus", "unknown", "UNCERTAIN"], ["msypublisher.com", "msypublisher", "unknown", "UNCERTAIN"], ["1manhwa.com", "1manhwa", "unknown", "UNCERTAIN"], ["imperioscans.com.br", "imperioscans", "unknown", "UNCERTAIN"], ["novelstown.com", "novelstown", "unknown", "UNCERTAIN"], ["birdtoon.shop", "birdtoon", "unknown", "UNCERTAIN"], ["mangasup.net", "mangasup", "unknown", "UNCERTAIN"], ["bakaman.net", "bakaman", "unknown", "UNCERTAIN"], ["www.rh2plusmanga.com", "www", "unknown", "UNCERTAIN"], ["mangacim.com.tr", "mangacim", "unknown", "UNCERTAIN"], ["www.aiyumanhua.com", "www", "unknown", "UNCERTAIN"]]
const BATCH = 9
const batches = []
for (let i = 0; i < recover.length; i += BATCH) batches.push(recover.slice(i, i + BATCH))

const RECOVER_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    results: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          oldDomain: { type: 'string', description: 'the dead domain from the input, verbatim' },
          newDomain: { type: ['string', 'null'], description: 'CURRENT official domain, hostname only (no scheme/path/www unless required); null if truly gone with no successor' },
          confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
          status: { type: 'string', enum: ['migrated', 'alive_same', 'dead_no_successor', 'uncertain'] },
          evidence: { type: 'string', description: 'one line: what proves this is the SAME source (redirect chain, official announcement, tracker, socials). Keep short.' },
        },
        required: ['oldDomain', 'newDomain', 'confidence', 'status', 'evidence'],
      },
    },
  },
  required: ['results'],
}

function recoverPrompt(batch) {
  const list = batch.map(([d, n, e, s], i) => `${i + 1}. name="${n}" oldDomain=${d} engine=${e} probe=${s}`).join('\n')
  return `You are recovering DEAD manga-reader source domains. Each site below is currently unreachable (dead DNS, parked, 404, or TLS-dead). Manga/scanlation sites hop domains constantly (e.g. nettruyen -> nettruyenXXXX.com, asurascans -> asuracomic.net), so most have a CURRENT domain.

For EACH entry, find the source's current official domain.

Use web search + page fetch. Load the WebSearch and WebFetch tools first (they are deferred): call ToolSearch with query "select:WebSearch,WebFetch". Then, per source:
- Search the brand name + "manga" (e.g. "MangaKoinu manga", "nettruyen moi nhat", "<name> new domain 2026").
- Search the old domain to find redirect/migration notices, Reddit/Discord/forum posts, or manga-site trackers.
- If a candidate domain appears, WebFetch it to confirm it is a live manga reader of the SAME brand (title/logo/name match), not a squatter, ad-parking page, unrelated aggregator, or a different source.

RULES (be conservative — a wrong domain is worse than null):
- newDomain = hostname ONLY (no https://, no trailing slash, no path). Include a leading subdomain only if that is the real host.
- confidence "high" ONLY with strong same-brand evidence (redirect from old domain, official announcement, or exact brand match on a live page). "medium" = plausible brand match but not confirmed live. "low" = weak/guess.
- If the source is genuinely gone (scanlation group disbanded, no successor, only unrelated results) -> newDomain=null, status="dead_no_successor".
- Do NOT invent domains. Do NOT map a source to a big aggregator that merely rehosts its content.
- Engines in a hopping family (wpcomics nettruyen cluster, madara, mangareader themesia) usually just changed the numeric suffix — find the newest working mirror.

Return one result object per input entry, oldDomain matching verbatim.

Entries:
${list}`
}

phase('Recover')
const recovered = await parallel(
  batches.map((batch, bi) => () =>
    agent(recoverPrompt(batch), { label: `recover:${bi + 1}/${batches.length}`, phase: 'Recover', schema: RECOVER_SCHEMA, model: 'sonnet' })
  )
)
const recoverFlat = recovered.filter(Boolean).flatMap(r => r.results || [])
log(`recovery: ${recoverFlat.length} results across ${batches.length} batches`)


return { recoverCount: recoverFlat.length, recover: recoverFlat }
