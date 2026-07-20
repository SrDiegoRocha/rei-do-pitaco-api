/**
 * Baixa escudos de clubes do football-logos.cc para logos/{país}/{slug}.png
 * e gera logos/manifest.json (insumo do generate-seed.ts).
 *
 * Uso:  npx tsx scripts/download-logos.ts [--countries brazil,england]
 *
 * Como funciona: a página de cada país (ex. https://football-logos.cc/brazil/)
 * lista todos os times agrupados por liga (<h2>), cada um com a <img> 256x256
 * já com o content-hash do CDN. Uma requisição por país entrega tudo; as URLs
 * não são previsíveis (hash por tamanho), por isso o parse do HTML.
 *
 * Re-execução é barata: PNGs já existentes no disco são pulados; o manifest é
 * sempre regravado por inteiro a partir do que foi parseado.
 */

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOGOS_DIR = path.join(REPO_ROOT, 'logos');
const MANIFEST_PATH = path.join(LOGOS_DIR, 'manifest.json');

const BASE_URL = 'https://football-logos.cc';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) rei-do-pitaco-logo-fetcher';
const DOWNLOAD_DELAY_MS = 150;

interface LeagueTarget {
  /** Texto exato do <h2> na página do país. */
  heading: string;
  slug: string;
  name: string;
}

interface CountryTarget {
  /** Slug do país no site (path da URL). */
  country: string;
  /** Código de bandeira (flagicons), igual ao country_code das seleções. */
  countryCode: string;
  leagues: LeagueTarget[];
}

/** Lote de ligas a baixar. Para adicionar uma liga, inclua aqui e rode de novo. */
const TARGETS: CountryTarget[] = [
  {
    country: 'brazil',
    countryCode: 'br',
    leagues: [
      { heading: 'Brazilian Serie A', slug: 'brasileirao-serie-a', name: 'Brasileirão Série A' },
      { heading: 'Brazilian Serie B', slug: 'brasileirao-serie-b', name: 'Brasileirão Série B' },
    ],
  },
  {
    country: 'england',
    countryCode: 'gb-eng',
    leagues: [{ heading: 'English Premier League', slug: 'premier-league', name: 'Premier League' }],
  },
  {
    country: 'spain',
    countryCode: 'es',
    leagues: [{ heading: 'La Liga', slug: 'la-liga', name: 'La Liga' }],
  },
  {
    country: 'italy',
    countryCode: 'it',
    leagues: [{ heading: 'Serie A', slug: 'serie-a', name: 'Serie A' }],
  },
  {
    country: 'germany',
    countryCode: 'de',
    leagues: [{ heading: 'Bundesliga', slug: 'bundesliga', name: 'Bundesliga' }],
  },
  {
    country: 'france',
    countryCode: 'fr',
    leagues: [{ heading: 'Ligue 1', slug: 'ligue-1', name: 'Ligue 1' }],
  },
  {
    country: 'portugal',
    countryCode: 'pt',
    leagues: [{ heading: 'Primeira Liga', slug: 'primeira-liga', name: 'Primeira Liga' }],
  },
  {
    country: 'netherlands',
    countryCode: 'nl',
    leagues: [{ heading: 'Eredivisie', slug: 'eredivisie', name: 'Eredivisie' }],
  },
  {
    country: 'argentina',
    countryCode: 'ar',
    leagues: [{ heading: 'Primera División', slug: 'primera-division-arg', name: 'Primera División' }],
  },
];

interface ManifestTeam {
  country: string;
  countryCode: string;
  leagueSlug: string;
  leagueName: string;
  teamSlug: string;
  teamName: string;
  /** Caminho relativo à raiz do repo, com "/" (é o que vira badge_url). */
  file: string;
  sourceUrl: string;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function decodeEntities(text: string): string {
  return text
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&nbsp;/g, ' ');
}

async function fetchText(url: string): Promise<string> {
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) throw new Error(`GET ${url} -> HTTP ${res.status}`);
  return res.text();
}

/**
 * Recorta o trecho do HTML entre o <h2> da liga e o próximo <h2> (ou o fim).
 * As páginas são minificadas, então tudo pode estar numa linha só.
 *
 * Também devolve o slug da própria liga: o <h2> fica dentro de um <a> que
 * aponta para a página da liga, e o primeiro card da grade é o escudo da liga
 * (mesma estrutura dos times) — o slug serve para pulá-lo no parse.
 */
function sliceLeagueSection(html: string, heading: string, country: string): { section: string; leagueSelfSlug: string | null } {
  const headingRe = new RegExp(`<h2[^>]*>\\s*${escapeRegex(heading)}\\s*</h2>`);
  const match = headingRe.exec(html);
  if (!match) throw new Error(`Seção "<h2>${heading}</h2>" não encontrada`);

  const before = html.slice(Math.max(0, match.index - 1000), match.index);
  const anchorRe = new RegExp(`href="/${escapeRegex(country)}/([^/"]+)/"`, 'g');
  let leagueSelfSlug: string | null = null;
  for (const anchor of before.matchAll(anchorRe)) {
    leagueSelfSlug = anchor[1];
  }

  const start = match.index + match[0].length;
  const nextH2 = html.indexOf('<h2', start);
  return { section: nextH2 === -1 ? html.slice(start) : html.slice(start, nextH2), leagueSelfSlug };
}

function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

interface ParsedTeam {
  slug: string;
  name: string;
  logoUrl: string;
}

/**
 * Extrai os times de uma seção de liga. Cada card tem a forma:
 *   <div ... data-logo-id="flamengo" ...>
 *     <a href="/brazil/flamengo/" ...>
 *       <img src="https://assets.football-logos.cc/logos/brazil/256x256/flamengo.9c3055f2.png" ...>
 *       <h3 ...> Flamengo </h3>
 */
function parseTeams(section: string, country: string, leagueHeading: string, leagueSelfSlug: string | null): ParsedTeam[] {
  const teamRe = new RegExp(
    'data-logo-id="([^"]+)"[^>]*>\\s*' +
      `<a href="/${escapeRegex(country)}/([^/"]+)/"[\\s\\S]{0,1200}?` +
      '<img src="(https://assets\\.football-logos\\.cc/logos/[^"]+?/256x256/[^"]+?\\.png)"[\\s\\S]{0,600}?' +
      '<h3[^>]*>\\s*([\\s\\S]*?)\\s*</h3>',
    'g',
  );
  const teams: ParsedTeam[] = [];
  for (const match of section.matchAll(teamRe)) {
    const [, logoId, hrefSlug, logoUrl, rawName] = match;
    // O primeiro card da seção é o escudo da própria liga, não de um time.
    if (hrefSlug === leagueSelfSlug || decodeEntities(rawName.trim()) === leagueHeading) continue;
    if (logoId !== hrefSlug) {
      console.warn(`  ! data-logo-id "${logoId}" difere do slug do link "${hrefSlug}" — usando o do link`);
    }
    teams.push({ slug: hrefSlug, name: decodeEntities(rawName.trim()), logoUrl });
  }
  return teams;
}

async function downloadLogo(url: string, destination: string): Promise<'downloaded' | 'skipped'> {
  if (existsSync(destination)) return 'skipped';
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) throw new Error(`GET ${url} -> HTTP ${res.status}`);
  const bytes = Buffer.from(await res.arrayBuffer());
  if (bytes.length < 100) throw new Error(`Arquivo suspeito (${bytes.length} bytes) em ${url}`);
  await writeFile(destination, bytes);
  await sleep(DOWNLOAD_DELAY_MS);
  return 'downloaded';
}

async function main() {
  const countriesArg = process.argv.find((arg) => arg.startsWith('--countries'));
  const onlyCountries = countriesArg?.split('=')[1]?.split(',').map((c) => c.trim());
  const targets = onlyCountries ? TARGETS.filter((t) => onlyCountries.includes(t.country)) : TARGETS;
  if (targets.length === 0) {
    console.error(`Nenhum país casa com --countries=${onlyCountries?.join(',')}`);
    process.exit(1);
  }

  const manifest: ManifestTeam[] = [];
  let downloaded = 0;
  let skipped = 0;

  for (const target of targets) {
    console.log(`\n== ${target.country}`);
    const html = await fetchText(`${BASE_URL}/${target.country}/`);

    for (const league of target.leagues) {
      const { section, leagueSelfSlug } = sliceLeagueSection(html, league.heading, target.country);
      const teams = parseTeams(section, target.country, league.heading, leagueSelfSlug);
      if (teams.length < 2) {
        throw new Error(`Só ${teams.length} time(s) parseado(s) em "${league.heading}" (${target.country}) — o HTML deve ter mudado`);
      }
      console.log(`  ${league.name}: ${teams.length} times`);

      const countryDir = path.join(LOGOS_DIR, target.country);
      await mkdir(countryDir, { recursive: true });

      for (const team of teams) {
        const fileName = `${team.slug}.png`;
        const result = await downloadLogo(team.logoUrl, path.join(countryDir, fileName));
        result === 'downloaded' ? downloaded++ : skipped++;
        manifest.push({
          country: target.country,
          countryCode: target.countryCode,
          leagueSlug: league.slug,
          leagueName: league.name,
          teamSlug: team.slug,
          teamName: team.name,
          file: `logos/${target.country}/${fileName}`,
          sourceUrl: team.logoUrl,
        });
      }
    }
  }

  // Times que aparecem em mais de uma liga do lote (não deve acontecer dentro do
  // mesmo país/temporada, mas é barato conferir).
  const seen = new Map<string, ManifestTeam>();
  for (const team of manifest) {
    const key = `${team.country}/${team.teamSlug}`;
    const first = seen.get(key);
    if (first) {
      console.warn(`  ! ${key} aparece em "${first.leagueName}" e "${team.leagueName}"`);
    } else {
      seen.set(key, team);
    }
  }

  await mkdir(LOGOS_DIR, { recursive: true });
  await writeFile(MANIFEST_PATH, JSON.stringify({ generatedAt: new Date().toISOString(), source: BASE_URL, teams: manifest }, null, 2) + '\n');

  console.log(`\nTotal: ${manifest.length} times | ${downloaded} baixados, ${skipped} já existiam`);
  console.log(`Manifest: ${path.relative(REPO_ROOT, MANIFEST_PATH)}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
