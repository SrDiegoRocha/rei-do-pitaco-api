/**
 * Gera a migration de seed dos clubes do sistema a partir de logos/manifest.json
 * (produzido pelo download-logos.ts).
 *
 * Uso:  npx tsx scripts/generate-seed.ts
 *
 * Saída: src/main/resources/db/migration/V29__seed_system_clubs.sql
 *
 * O SQL é idempotente (mesmo padrão da V22): cada clube só entra se ainda não
 * existir um time do sistema ativo com o mesmo nome (case-insensitive).
 *
 * Curadoria embutida:
 *  - NAME_OVERRIDES: typos do site e convenções PT-BR.
 *  - SHORT_NAME_OVERRIDES: siglas consagradas; o resto sai de heurística.
 *  - COLOR_OVERRIDES: cores aproximadas dos clubes conhecidos; default neutro.
 *  - Colisões de nome (entre clubes e contra as seleções das V17/V21/V22) são
 *    qualificadas automaticamente com o país e avisadas no console.
 */

import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MANIFEST_PATH = path.join(REPO_ROOT, 'logos', 'manifest.json');
const MIGRATIONS_DIR = path.join(REPO_ROOT, 'src', 'main', 'resources', 'db', 'migration');
const OUTPUT_PATH = path.join(MIGRATIONS_DIR, 'V29__seed_system_clubs.sql');
const NATIONAL_TEAM_MIGRATIONS = [
  'V17__system_teams_and_world_cup_2026.sql',
  'V21__add_italy_national_team.sql',
  'V22__add_more_national_teams.sql',
];

const DEFAULT_PRIMARY = '#374151';
const DEFAULT_SECONDARY = '#F9FAFB';

interface ManifestTeam {
  country: string;
  countryCode: string;
  leagueSlug: string;
  leagueName: string;
  teamSlug: string;
  teamName: string;
  file: string;
  sourceUrl: string;
}

/** Typos do site e convenções de nome em PT-BR. Chave: country/teamSlug. */
const NAME_OVERRIDES: Record<string, string> = {
  'brazil/botafogo': 'Botafogo',
  'brazil/santos': 'Santos',
  'brazil/clube-do-remo': 'Remo',
  'brazil/avai': 'Avaí',
  'brazil/crb': 'CRB',
  'brazil/novorizontino': 'Novorizontino',
  'brazil/botafogo-sp': 'Botafogo-SP',
  'brazil/athletic': 'Athletic-MG',
  'england/liverpool': 'Liverpool',
  'spain/atletico-madrid': 'Atlético de Madrid',
  'spain/athletic-club': 'Athletic Bilbao',
  'spain/celta': 'Celta de Vigo',
  'germany/bayern-munchen': 'Bayern de Munique',
  'germany/koln': 'Colônia',
  'france/paris-saint-germain': 'Paris Saint-Germain',
  'france/lyon': 'Lyon',
  'portugal/famalicao': 'Famalicão',
  'netherlands/sc-heerenveen': 'SC Heerenveen',
  'netherlands/nec-nijmegen': 'NEC Nijmegen',
  'argentina/argeninos-juniors': 'Argentinos Juniors', // typo do site
  'argentina/club-atletico-platanense': 'Platense', // typo do site ("Platanense")
  'argentina/ca-huracan': 'Huracán',
  'argentina/union': 'Unión de Santa Fe',
  'argentina/gimnasia-lp': 'Gimnasia La Plata',
  'argentina/gimnasia-y-esgrima': 'Gimnasia y Esgrima de Mendoza',
  'argentina/estudiantes-de-rio-cuarto': 'Estudiantes de Río Cuarto',
};

/** Siglas consagradas. O resto sai da heurística (3 primeiras letras úteis). */
const SHORT_NAME_OVERRIDES: Record<string, string> = {
  // Brasil
  'brazil/flamengo': 'FLA', 'brazil/palmeiras': 'PAL', 'brazil/sao-paulo': 'SAO',
  'brazil/corinthians': 'COR', 'brazil/santos': 'SAN', 'brazil/vasco-da-gama': 'VAS',
  'brazil/botafogo': 'BOT', 'brazil/fluminense': 'FLU', 'brazil/gremio': 'GRE',
  'brazil/internacional': 'INT', 'brazil/cruzeiro': 'CRU', 'brazil/atletico-mineiro': 'CAM',
  'brazil/athletico-paranaense': 'CAP', 'brazil/bahia': 'BAH', 'brazil/vitoria': 'VIT',
  'brazil/coritiba': 'CTB', 'brazil/rb-bragantino': 'RBB', 'brazil/clube-do-remo': 'REM',
  'brazil/sport-recife': 'SPT', 'brazil/botafogo-sp': 'BSP', 'brazil/atletico-goianiense': 'ACG',
  'brazil/america-mineiro': 'AME', 'brazil/operario-ferroviario': 'OPE', 'brazil/ponte-preta': 'PON',
  // Inglaterra
  'england/arsenal': 'ARS', 'england/aston-villa': 'AVL', 'england/chelsea': 'CHE',
  'england/everton': 'EVE', 'england/liverpool': 'LIV', 'england/manchester-city': 'MCI',
  'england/manchester-united': 'MUN', 'england/newcastle': 'NEW', 'england/tottenham': 'TOT',
  'england/brighton': 'BHA', 'england/crystal-palace': 'CRY', 'england/nottingham-forest': 'NFO',
  'england/leeds-united': 'LEE', 'england/ipswich': 'IPS',
  // Espanha
  'spain/barcelona': 'BAR', 'spain/real-madrid': 'RMA', 'spain/atletico-madrid': 'ATM',
  'spain/sevilla': 'SEV', 'spain/real-betis': 'BET', 'spain/real-sociedad': 'RSO',
  'spain/athletic-club': 'ATH', 'spain/deportivo': 'ALA', 'spain/deportivo-la-coruna': 'DEP',
  'spain/rayo-vallecano': 'RAY',
  // Itália
  'italy/juventus': 'JUV', 'italy/milan': 'MIL', 'italy/inter': 'INT', 'italy/napoli': 'NAP',
  'italy/roma': 'ROM', 'italy/lazio': 'LAZ', 'italy/fiorentina': 'FIO', 'italy/atalanta': 'ATA',
  // Alemanha
  'germany/bayern-munchen': 'BAY', 'germany/borussia-dortmund': 'BVB', 'germany/rb-leipzig': 'RBL',
  'germany/bayer-leverkusen': 'B04', 'germany/eintracht-frankfurt': 'SGE', 'germany/schalke-04': 'S04',
  'germany/hamburger-sv': 'HSV', 'germany/werder-bremen': 'SVW', 'germany/borussia-monchengladbach': 'BMG',
  'germany/vfb-stuttgart': 'VFB', 'germany/mainz-05': 'M05', 'germany/koln': 'KOE',
  'germany/hoffenheim': 'TSG', 'germany/freiburg': 'SCF',
  // França
  'france/paris-saint-germain': 'PSG', 'france/marseille': 'MAR', 'france/lyon': 'LYO',
  'france/as-monaco': 'ASM', 'france/lille': 'LIL', 'france/rc-lens': 'RCL',
  'france/rc-strasbourg-alsace': 'STR', 'france/paris-fc': 'PFC',
  // Portugal
  'portugal/benfica': 'SLB', 'portugal/fc-porto': 'FCP', 'portugal/sporting-cp': 'SCP',
  'portugal/sc-braga': 'SCB', 'portugal/vitoria-de-guimaraes': 'VSC',
  // Países Baixos
  'netherlands/psv': 'PSV', 'netherlands/ajax': 'AJA', 'netherlands/feyenoord': 'FEY',
  'netherlands/az-alkmaar': 'AZ', 'netherlands/fc-utrecht': 'UTR', 'netherlands/twente': 'TWE',
  'netherlands/nec-nijmegen': 'NEC', 'netherlands/pec-zwolle': 'PEC', 'netherlands/go-ahead-eagles': 'GAE',
  // Argentina
  'argentina/boca-juniors': 'BOC', 'argentina/river-plate': 'RIV', 'argentina/racing-club': 'RAC',
  'argentina/independiente': 'IND', 'argentina/san-lorenzo-de-almagro': 'SLO',
  'argentina/estudiantes-de-la-plata': 'EST', 'argentina/velez-sarsfield': 'VEL',
  'argentina/newells-old-boys': 'NOB', 'argentina/rosario-central': 'ROS',
  'argentina/lanus': 'LAN', 'argentina/talleres': 'TAL', 'argentina/ca-huracan': 'HUR',
};

/** Cores aproximadas (manto/escudo) dos clubes conhecidos. Default neutro no resto. */
const COLOR_OVERRIDES: Record<string, [string, string]> = {
  // Brasil — Série A
  'brazil/flamengo': ['#E30613', '#000000'],
  'brazil/palmeiras': ['#006437', '#FFFFFF'],
  'brazil/sao-paulo': ['#FE0000', '#000000'],
  'brazil/corinthians': ['#000000', '#FFFFFF'],
  'brazil/santos': ['#000000', '#FFFFFF'],
  'brazil/vasco-da-gama': ['#000000', '#FFFFFF'],
  'brazil/botafogo': ['#000000', '#FFFFFF'],
  'brazil/fluminense': ['#7A0C2E', '#00613C'],
  'brazil/gremio': ['#0D80BF', '#000000'],
  'brazil/internacional': ['#E5050F', '#FFFFFF'],
  'brazil/cruzeiro': ['#003399', '#FFFFFF'],
  'brazil/atletico-mineiro': ['#000000', '#FFFFFF'],
  'brazil/athletico-paranaense': ['#C8102E', '#000000'],
  'brazil/bahia': ['#006CB5', '#ED3237'],
  'brazil/vitoria': ['#FF0000', '#000000'],
  'brazil/coritiba': ['#00544E', '#FFFFFF'],
  'brazil/rb-bragantino': ['#FFFFFF', '#E00034'],
  'brazil/clube-do-remo': ['#00278B', '#FFFFFF'],
  'brazil/mirassol': ['#FFD100', '#00693C'],
  'brazil/chapecoense': ['#009846', '#FFFFFF'],
  // Brasil — Série B
  'brazil/america-mineiro': ['#007A33', '#FFFFFF'],
  'brazil/atletico-goianiense': ['#DC1E28', '#000000'],
  'brazil/avai': ['#00679A', '#FFFFFF'],
  'brazil/botafogo-sp': ['#DA291C', '#FFFFFF'],
  'brazil/ceara': ['#000000', '#FFFFFF'],
  'brazil/crb': ['#DA291C', '#FFFFFF'],
  'brazil/criciuma': ['#FFD700', '#000000'],
  'brazil/cuiaba': ['#FDB913', '#00613C'],
  'brazil/fortaleza': ['#006CB5', '#DA291C'],
  'brazil/goias': ['#00693C', '#FFFFFF'],
  'brazil/juventude': ['#009846', '#FFFFFF'],
  'brazil/londrina': ['#0047AB', '#FFFFFF'],
  'brazil/nautico': ['#DA291C', '#FFFFFF'],
  'brazil/novorizontino': ['#FFD100', '#000000'],
  'brazil/operario-ferroviario': ['#000000', '#FFFFFF'],
  'brazil/ponte-preta': ['#000000', '#FFFFFF'],
  'brazil/sao-bernardo': ['#FFD100', '#000000'],
  'brazil/sport-recife': ['#DA291C', '#000000'],
  'brazil/vila-nova': ['#DA291C', '#FFFFFF'],
  'brazil/athletic': ['#DA291C', '#000000'],
  // Inglaterra
  'england/arsenal': ['#EF0107', '#FFFFFF'],
  'england/aston-villa': ['#670E36', '#95BFE5'],
  'england/chelsea': ['#034694', '#FFFFFF'],
  'england/everton': ['#003399', '#FFFFFF'],
  'england/liverpool': ['#C8102E', '#FFFFFF'],
  'england/manchester-city': ['#6CABDD', '#FFFFFF'],
  'england/manchester-united': ['#DA291C', '#000000'],
  'england/newcastle': ['#241F20', '#FFFFFF'],
  'england/tottenham': ['#FFFFFF', '#132257'],
  'england/bournemouth': ['#DA291C', '#000000'],
  'england/brentford': ['#E30613', '#FFFFFF'],
  'england/brighton': ['#0057B8', '#FFFFFF'],
  'england/coventry-city': ['#78D2F2', '#FFFFFF'],
  'england/crystal-palace': ['#1B458F', '#C4122E'],
  'england/fulham': ['#FFFFFF', '#000000'],
  'england/hull-city': ['#F5971D', '#000000'],
  'england/ipswich': ['#0044A9', '#FFFFFF'],
  'england/leeds-united': ['#FFFFFF', '#1D428A'],
  'england/nottingham-forest': ['#DD0000', '#FFFFFF'],
  'england/sunderland': ['#EB172B', '#FFFFFF'],
  // Espanha
  'spain/barcelona': ['#A50044', '#004D98'],
  'spain/real-madrid': ['#FFFFFF', '#FEBE10'],
  'spain/atletico-madrid': ['#CB3524', '#FFFFFF'],
  'spain/sevilla': ['#FFFFFF', '#D8171E'],
  'spain/valencia': ['#FFFFFF', '#EE3524'],
  'spain/villarreal': ['#FFE667', '#005187'],
  'spain/real-betis': ['#00954C', '#FFFFFF'],
  'spain/real-sociedad': ['#0067B1', '#FFFFFF'],
  'spain/athletic-club': ['#EE2523', '#FFFFFF'],
  'spain/espanyol': ['#007FC8', '#FFFFFF'],
  'spain/celta': ['#8AC3EE', '#FFFFFF'],
  'spain/deportivo': ['#0761AF', '#FFFFFF'],
  'spain/deportivo-la-coruna': ['#005CA5', '#FFFFFF'],
  'spain/elche': ['#00A650', '#FFFFFF'],
  'spain/getafe': ['#005999', '#FFFFFF'],
  'spain/levante': ['#B4053F', '#005999'],
  'spain/malaga': ['#0072BC', '#FFFFFF'],
  'spain/osasuna': ['#D91A21', '#0A346F'],
  'spain/racing': ['#00A651', '#FFFFFF'],
  'spain/rayo-vallecano': ['#FFFFFF', '#E53027'],
  // Itália
  'italy/juventus': ['#000000', '#FFFFFF'],
  'italy/milan': ['#FB090B', '#000000'],
  'italy/inter': ['#0068A8', '#000000'],
  'italy/napoli': ['#12A0D7', '#FFFFFF'],
  'italy/roma': ['#8E1F2F', '#F0BC42'],
  'italy/lazio': ['#87D8F7', '#FFFFFF'],
  'italy/fiorentina': ['#482E92', '#FFFFFF'],
  'italy/atalanta': ['#1E71B8', '#000000'],
  'italy/bologna': ['#1A2F48', '#D4001F'],
  'italy/cagliari': ['#B01028', '#002350'],
  'italy/genoa': ['#AE1919', '#00285E'],
  'italy/torino': ['#8B1B23', '#FFFFFF'],
  'italy/udinese': ['#000000', '#FFFFFF'],
  'italy/parma': ['#FFD700', '#034694'],
  'italy/monza': ['#EE0E36', '#FFFFFF'],
  'italy/como-1907': ['#00306B', '#FFFFFF'],
  'italy/venezia': ['#000000', '#F58220'],
  'italy/lecce': ['#FFD700', '#DA291C'],
  'italy/sassuolo': ['#00A752', '#000000'],
  'italy/frosinone': ['#FFD700', '#005CA9'],
  // Alemanha
  'germany/bayern-munchen': ['#DC052D', '#FFFFFF'],
  'germany/borussia-dortmund': ['#FDE100', '#000000'],
  'germany/rb-leipzig': ['#FFFFFF', '#DD0741'],
  'germany/bayer-leverkusen': ['#E32221', '#000000'],
  'germany/eintracht-frankfurt': ['#E1000F', '#000000'],
  'germany/union-berlin': ['#EB1923', '#FFFFFF'],
  'germany/schalke-04': ['#004D9D', '#FFFFFF'],
  'germany/hamburger-sv': ['#FFFFFF', '#0A3F86'],
  'germany/augsburg': ['#BA3733', '#46714D'],
  'germany/werder-bremen': ['#1D9053', '#FFFFFF'],
  'germany/freiburg': ['#000000', '#DD0741'],
  'germany/hoffenheim': ['#1C63B7', '#FFFFFF'],
  'germany/koln': ['#ED1C24', '#FFFFFF'],
  'germany/mainz-05': ['#C3141E', '#FFFFFF'],
  'germany/borussia-monchengladbach': ['#000000', '#00A651'],
  'germany/vfb-stuttgart': ['#FFFFFF', '#E32219'],
  'germany/sv-elversberg': ['#005CA9', '#FFFFFF'],
  'germany/paderborn': ['#005CA9', '#000000'],
  // França
  'france/paris-saint-germain': ['#004170', '#DA291C'],
  'france/marseille': ['#FFFFFF', '#2FAEE0'],
  'france/lyon': ['#FFFFFF', '#DA001A'],
  'france/as-monaco': ['#ED1C24', '#FFFFFF'],
  'france/lille': ['#E01E13', '#000E42'],
  'france/rc-lens': ['#FFD700', '#EC1C24'],
  'france/nice': ['#ED1C24', '#000000'],
  'france/rennes': ['#E13327', '#000000'],
  'france/rc-strasbourg-alsace': ['#009FE3', '#FFFFFF'],
  'france/toulouse': ['#615395', '#FFFFFF'],
  'france/brest': ['#E30613', '#FFFFFF'],
  'france/auxerre': ['#FFFFFF', '#003C7E'],
  'france/angers': ['#000000', '#FFFFFF'],
  'france/le-havre-ac': ['#75AADB', '#0D1F41'],
  'france/lorient': ['#F36F21', '#000000'],
  'france/paris-fc': ['#00337F', '#FFFFFF'],
  'france/troyes': ['#005CA9', '#FFFFFF'],
  'france/le-mans': ['#FFD100', '#B01028'],
  // Portugal
  'portugal/benfica': ['#E20E17', '#FFFFFF'],
  'portugal/fc-porto': ['#00428C', '#FFFFFF'],
  'portugal/sporting-cp': ['#008057', '#FFFFFF'],
  'portugal/sc-braga': ['#DA291C', '#FFFFFF'],
  'portugal/vitoria-de-guimaraes': ['#FFFFFF', '#000000'],
  'portugal/maritimo': ['#009846', '#DA291C'],
  'portugal/rio-ave': ['#00693C', '#FFFFFF'],
  'portugal/famalicao': ['#00306B', '#FFFFFF'],
  'portugal/gil-vicente': ['#DA291C', '#00306B'],
  'portugal/estoril': ['#FFD700', '#00306B'],
  'portugal/moreirense': ['#00693C', '#FFD700'],
  'portugal/santa-clara': ['#DA291C', '#FFFFFF'],
  'portugal/casa-pia-ac': ['#000000', '#FFFFFF'],
  'portugal/arouca': ['#FFD700', '#00306B'],
  'portugal/nacional-da-madeira': ['#000000', '#FFFFFF'],
  'portugal/estrela-da-amadora': ['#DA291C', '#009846'],
  'portugal/alverca': ['#DA291C', '#FFFFFF'],
  'portugal/academico-de-viseu': ['#00693C', '#FFFFFF'],
  // Países Baixos
  'netherlands/psv': ['#ED1C24', '#FFFFFF'],
  'netherlands/ajax': ['#D2122E', '#FFFFFF'],
  'netherlands/feyenoord': ['#E01E28', '#FFFFFF'],
  'netherlands/az-alkmaar': ['#DA291C', '#FFFFFF'],
  'netherlands/fc-utrecht': ['#DA291C', '#FFFFFF'],
  'netherlands/twente': ['#DA291C', '#FFFFFF'],
  'netherlands/fc-groningen': ['#009846', '#FFFFFF'],
  'netherlands/sc-heerenveen': ['#004FA3', '#FFFFFF'],
  'netherlands/sparta-rotterdam': ['#DA291C', '#FFFFFF'],
  'netherlands/willem-ii': ['#DA291C', '#003C7E'],
  'netherlands/pec-zwolle': ['#004FA3', '#FFFFFF'],
  'netherlands/nec-nijmegen': ['#DA291C', '#009846'],
  'netherlands/go-ahead-eagles': ['#DA291C', '#FFD700'],
  'netherlands/fortuna-sittard': ['#FFD700', '#009846'],
  'netherlands/excelsior-rotterdam': ['#000000', '#DA291C'],
  'netherlands/ado-den-haag': ['#009846', '#FFD700'],
  'netherlands/telstar': ['#FFFFFF', '#000000'],
  'netherlands/sc-cambuur': ['#FFD700', '#00306B'],
  // Argentina
  'argentina/boca-juniors': ['#003B94', '#FDB913'],
  'argentina/river-plate': ['#FFFFFF', '#EB192D'],
  'argentina/racing-club': ['#75AADB', '#FFFFFF'],
  'argentina/independiente': ['#E10613', '#FFFFFF'],
  'argentina/san-lorenzo-de-almagro': ['#00306B', '#DA291C'],
  'argentina/estudiantes-de-la-plata': ['#DA291C', '#FFFFFF'],
  'argentina/velez-sarsfield': ['#FFFFFF', '#00306B'],
  'argentina/newells-old-boys': ['#DA291C', '#000000'],
  'argentina/rosario-central': ['#FDB913', '#00306B'],
  'argentina/lanus': ['#800000', '#FFFFFF'],
  'argentina/talleres': ['#00306B', '#FFFFFF'],
  'argentina/ca-huracan': ['#FFFFFF', '#DA291C'],
  'argentina/banfield': ['#009846', '#FFFFFF'],
  'argentina/belgrano': ['#75AADB', '#000000'],
  'argentina/tigre': ['#00306B', '#DA291C'],
  'argentina/union': ['#DA291C', '#FFFFFF'],
  'argentina/gimnasia-lp': ['#FFFFFF', '#00306B'],
  'argentina/atletico-tucuman': ['#75AADB', '#FFFFFF'],
  'argentina/defensa-y-justicia': ['#FFD700', '#009846'],
  'argentina/argeninos-juniors': ['#DA291C', '#FFFFFF'],
  'argentina/barracas-central': ['#DA291C', '#FFFFFF'],
  'argentina/central-cordoba': ['#000000', '#FFFFFF'],
  'argentina/instituto-cordoba': ['#DA291C', '#FFFFFF'],
  'argentina/sarmiento': ['#009846', '#FFFFFF'],
  'argentina/aldosivi': ['#FFD700', '#009846'],
  'argentina/club-atletico-platanense': ['#8B4513', '#FFFFFF'],
  'argentina/deportivo-riestra': ['#000000', '#FFFFFF'],
  'argentina/independiente-rivadavia': ['#00306B', '#FFFFFF'],
  'argentina/gimnasia-y-esgrima': ['#FFFFFF', '#000000'],
  'argentina/estudiantes-de-rio-cuarto': ['#75AADB', '#FFFFFF'],
};

/** Palavras genéricas ignoradas ao derivar a sigla. */
const GENERIC_PREFIXES = new Set(['fc', 'sc', 'ca', 'cd', 'ac', 'as', 'cf', 'sv', 'rc', 'afc', 'sl', 'clube', 'club', 'cr']);

function stripAccents(text: string): string {
  return text.normalize('NFD').replace(new RegExp('[\\u0300-\\u036f]', 'g'), '');
}

function deriveShortName(name: string): string {
  const words = stripAccents(name)
    .replace(/[^a-zA-Z0-9 ]/g, ' ')
    .split(/\s+/)
    .filter(Boolean);
  const significant = words.filter((word, index) => index === words.length - 1 || !GENERIC_PREFIXES.has(word.toLowerCase()));
  const base = (significant[0] ?? words[0] ?? '').toUpperCase();
  if (base.length >= 3) return base.slice(0, 3);
  const next = (significant[1] ?? '').toUpperCase();
  return (base + next).slice(0, 3).padEnd(2, 'X');
}

function sqlQuote(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

/** Extrai o primeiro literal string de cada tupla VALUES das migrations de seleções. */
async function loadNationalTeamNames(): Promise<Set<string>> {
  const names = new Set<string>();
  for (const fileName of NATIONAL_TEAM_MIGRATIONS) {
    const sql = await readFile(path.join(MIGRATIONS_DIR, fileName), 'utf8');
    for (const match of sql.matchAll(/\(\s*(?:gen_random_uuid\(\),\s*)?'((?:[^']|'')+)'/g)) {
      names.add(match[1].replace(/''/g, "'").toLowerCase());
    }
  }
  return names;
}

async function main() {
  const manifest = JSON.parse(await readFile(MANIFEST_PATH, 'utf8')) as { teams: ManifestTeam[] };
  const nationalNames = await loadNationalTeamNames();

  const countryLabel: Record<string, string> = {
    brazil: 'Brasil', england: 'Inglaterra', spain: 'Espanha', italy: 'Itália', germany: 'Alemanha',
    france: 'França', portugal: 'Portugal', netherlands: 'Países Baixos', argentina: 'Argentina',
  };

  interface Row {
    name: string;
    shortName: string;
    badgeUrl: string;
    primary: string;
    secondary: string;
    countryCode: string;
    leagueSlug: string;
    leagueName: string;
  }

  const rows: Row[] = [];
  const usedNames = new Map<string, string>(); // lower(name) -> country/slug

  for (const team of manifest.teams) {
    const key = `${team.country}/${team.teamSlug}`;
    let name = NAME_OVERRIDES[key] ?? team.teamName;

    if (nationalNames.has(name.toLowerCase())) {
      const qualified = `${name} (${countryLabel[team.country] ?? team.country})`;
      console.warn(`! "${name}" colide com uma seleção — renomeado para "${qualified}"`);
      name = qualified;
    }
    const clash = usedNames.get(name.toLowerCase());
    if (clash) {
      const qualified = `${name} (${countryLabel[team.country] ?? team.country})`;
      console.warn(`! "${name}" (${key}) colide com ${clash} — renomeado para "${qualified}"`);
      name = qualified;
    }
    usedNames.set(name.toLowerCase(), key);

    const [primary, secondary] = COLOR_OVERRIDES[key] ?? [DEFAULT_PRIMARY, DEFAULT_SECONDARY];
    if (!COLOR_OVERRIDES[key]) {
      console.warn(`  (cores default para ${key})`);
    }

    rows.push({
      name,
      shortName: SHORT_NAME_OVERRIDES[key] ?? deriveShortName(name),
      badgeUrl: `/${team.file.replace(/\\/g, '/')}`,
      primary,
      secondary,
      countryCode: team.countryCode,
      leagueSlug: team.leagueSlug,
      leagueName: team.leagueName,
    });
  }

  const lines: string[] = [];
  lines.push('-- Clubes do sistema (escudos self-hosted em /logos/**), agrupados por liga.');
  lines.push('-- GERADO por scripts/generate-seed.ts a partir de logos/manifest.json — revisado à mão.');
  lines.push('-- badge_url é caminho RELATIVO; a API prefixa com reidopitaco.assets.base-url na leitura.');
  lines.push('-- Cores aproximadas dos mantos/escudos (ajustáveis). Siglas sem pretensão de código oficial.');
  lines.push('--');
  lines.push('-- Idempotente (padrão da V22): só insere se não houver time do sistema ativo com o mesmo nome.');
  lines.push('INSERT INTO teams');
  lines.push('    (public_id, name, short_name, badge_url, primary_color, secondary_color, country_code, league_slug, league_name, is_system, team_type, active, created_at, updated_at)');
  lines.push('SELECT');
  lines.push('    gen_random_uuid(), v.name, v.short_name, v.badge_url, v.primary_color, v.secondary_color, v.country_code, v.league_slug, v.league_name,');
  lines.push("    TRUE, 'CLUB', TRUE, now(), now()");
  lines.push('FROM (VALUES');

  const valueLines: string[] = [];
  let currentLeague = '';
  for (const row of rows) {
    if (row.leagueSlug !== currentLeague) {
      currentLeague = row.leagueSlug;
      valueLines.push(`    -- ${row.leagueName} (${row.countryCode})`);
    }
    valueLines.push(
      `    (${sqlQuote(row.name)}, ${sqlQuote(row.shortName)}, ${sqlQuote(row.badgeUrl)}, ` +
        `${sqlQuote(row.primary)}, ${sqlQuote(row.secondary)}, ${sqlQuote(row.countryCode)}, ` +
        `${sqlQuote(row.leagueSlug)}, ${sqlQuote(row.leagueName)}),`,
    );
  }
  // Remove a vírgula da última tupla (comentários podem vir depois dela).
  for (let i = valueLines.length - 1; i >= 0; i--) {
    if (!valueLines[i].trimStart().startsWith('--')) {
      valueLines[i] = valueLines[i].replace(/,$/, '');
      break;
    }
  }
  lines.push(...valueLines);

  lines.push(') AS v(name, short_name, badge_url, primary_color, secondary_color, country_code, league_slug, league_name)');
  lines.push('WHERE NOT EXISTS (');
  lines.push('    SELECT 1 FROM teams t');
  lines.push('    WHERE t.is_system = TRUE AND t.active = TRUE');
  lines.push('      AND LOWER(t.name) = LOWER(v.name)');
  lines.push(');');
  lines.push('');

  await writeFile(OUTPUT_PATH, lines.join('\n'), 'utf8');
  console.log(`\n${rows.length} clubes -> ${path.relative(REPO_ROOT, OUTPUT_PATH)}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
