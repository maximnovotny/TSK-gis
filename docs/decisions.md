# Rozhodnutí a odchylky od PRD

Každý bod říká, co se odchýlilo od zadání, proč, a co by změnu vrátilo zpět.

## Model: `claude-opus-5` místo Claude 3.5 Sonnet

PRD zmiňuje Claude 3.5 Sonnet. Ten model byl mezitím vyřazen a API na jeho
identifikátor vrací 404. Výchozí model je proto `claude-opus-5`; levnější
alternativou pro vysoký objem je `claude-sonnet-5`. Jméno modelu je jen hodnota
proměnné `ANTHROPIC_MODEL`, takže změna nevyžaduje zásah do kódu.

## Frontend: React, ne Flutter

PRD uvádí „React / Flutter" jako alternativu, ale zbytek sekce 4 volbu určuje
sám: HeroUI je React knihovna a Monaco Editor je webová komponenta. Flutter by
znamenal buď obojí nahradit, nebo Monaco vkládat přes webview.

## HeroUI v3: použito na povrchy, ne na formulářové prvky

Aktuální HeroUI je v3 — compound API nad `react-aria-components`, jiné než
dokumentovaná v2 (`onPress` místo `onClick`, `Card.Root`/`Card.Header`, …).
Použité jsou `Button`, `Card`, `Chip` a `Spinner`. Select a textarea jsou
nativní prvky nastylované do stejné palety: v hustém nástroji si drží nativní
klávesové chování a nepřekrývají editor plovoucím popoverem. Vizuální jazyk je
tedy HeroUI, ovládání formulářů ne — což odpovídá formulaci „postavený na
principech HeroUI".

## Monaco je zabalený lokálně, ne z CDN

`@monaco-editor/react` výchozím nastavením stahuje editor za běhu z jsdelivr.
V air-gapped on-premise instalaci by aplikace nefungovala vůbec a na stroji,
jehož smyslem je neposílat nic ven, by to byl odchozí požadavek. Monaco se proto
importuje jako modul.

Importuje se `editor.api`, ne kořen balíčku: tím vypadnou jazykové služby pro
TypeScript, CSS, HTML a JSON (~9 MB workerů), které pro naše cíle nemají využití.
Zvýrazňování syntaxe pro Python, C# a Scheme zůstává a načítá se až při otevření
příslušného skriptu. Hlavní bundle má ~2,9 MB (772 kB gzip) — to je cena za
editor, který funguje offline.

AutoLISP nemá v Monaku vlastní režim; používá se Scheme, který korektně
zvýrazňuje s-výrazy a závorky. Vlastní tokenizer pro AutoLISP je otevřená položka.

## Odpovědi RAG: raději prázdno než smyšlenka

`NullRetriever` vrací prázdný seznam a v `/api/health` se hlásí jako
nepřipravený. Generování přes retriever prochází už teď, takže M3 se dotkne
jen balíčku `app/rag/`. Vědomě tam není žádný „dočasný" retriever nad
nezaindexovanou bází — nesprávná signatura API, která vypadá věrohodně, je
nejdražší chyba, jakou tenhle produkt může vydat.

## Databáze: SQLite lokálně, PostgreSQL v compose

`DATABASE_URL` má výchozí hodnotu SQLite, takže `uvicorn app.main:app` běží bez
jediné spuštěné služby. Compose ji přepíše na PostgreSQL. Schéma se vytváří při
startu přes `create_all`, ne migracemi — vědomé zjednodušení MVP a první věc
k nahrazení Alembicem, jakmile se model snippetu přestane hýbat.

## Tagy jako JSON sloupec

Tagy jsou denormalizované v JSON sloupci, filtrování probíhá v Pythonu. Drží to
SQLite i PostgreSQL na stejné cestě kódu a při stovkách skriptů to stačí.
Až přestane, patří tagy do vlastní tabulky s indexem.

## Přílohy jako text v JSON těle, ne multipart

Parametry z Revitu, CSV exporty a logy jsou text. Čtou se v prohlížeči a posílají
se v jednom JSON těle. Odpadá tím druhá cesta se samostatnými limity. Soubor nad
100 000 znaků se zkrátí a **zkrácení je vepsané do obsahu** — model nesmí
uvažovat nad půlkou logu a znít si přitom jistě.

## Co ještě není hotové

- **M3 RAG** — indexace Autodesk dokumentace, embeddingy, Qdrant retriever.
  Qdrant v compose běží, ale nic ho zatím neplní.
- **Autentizace a multi-tenancy.** Knihovna skriptů je teď společná pro celou
  instanci. Pro B2B SaaS to je nutná podmínka, pro self-hosted instalaci jednoho
  týmu ne.
- **Perzistence běhů.** Historie generování se nikam neukládá.
- **Rate limiting** na `/api/generate`.
