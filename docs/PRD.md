# PRODUCT REQUIREMENT DOCUMENT (PRD) • ELYS

**AI CAD/BIM Script Builder** — komplexní zadání pro vývoj SaaS platformy na
automatizaci inženýrských skriptů.

> Zdrojový dokument tak, jak byl zadán. Odchylky implementace od tohoto textu
> jsou vypsané v [`docs/decisions.md`](./decisions.md).

## 1. Shrnutí a vize produktu

Cílem je vytvořit B2B SaaS platformu pro automatizované generování, ladění a
optimalizaci skriptů pro geodetické, inženýrské a architektonické softwary.
Nástroj primárně obslouží generování AutoLISP rutin (AutoCAD / Civil 3D),
pyRevit/C# maker a Dynamo skriptů (Revit). Platforma zkrátí čas potřebný pro
vývoj interních automatizací z dnů na minuty pomocí LLM a kontextové RAG
pipeline, navržená v minimalistickém a vysoce funkčním prostředí.

## 2. Klíčové uživatelské role (Cílová skupina)

- **BIM Manažeři & Koordinátoři:** Hledají automatizaci rutinních úkolů
  (hromadné přepisování parametrů v Revitu, QA/QC modelů).
- **Geodeti & Civilní inženýři:** Potřebují rychlé LISP rutiny pro práci s
  digitálními modely terénu (DTM), redukci vertexů (algoritmy typu
  Ramer-Douglas-Peucker) a přesuny či konverze datových formátů (DXF/CSV logy).
- **VDC Software Developers:** Využijí nástroj jako pokročilé IDE s integrovaným
  AI asistentem pro rychlejší iteraci a psaní C# pluginů přes Revit API.

## 3. Architektura & Technologický Stack

Architektura je navržena jako privacy-first řešení s důrazem na maximální
kontrolu nad daty, snadný self-hosting a bezproblémovou škálovatelnost.

### 3.1 Infrastruktura a Deployment

Nasazení bude řízeno kontejnerově na dedikovaných serverech.

VPS · Docker Compose · Coolify · Traefik

### 3.2 Backend a Databáze

Základní vrstva pro API a orchestraci AI agentů.

Python (FastAPI) · React / Flutter · PostgreSQL · Qdrant / Milvus

### 3.3 AI a RAG Pipeline

**Fázování modelů:** V MVP fázi bude využito API cloudového modelu
optimalizovaného na kód (např. Claude 3.5 Sonnet). Architektura však musí být
modulární pro budoucí nasazení Ollama s kvantovanými modely (např. Gemma) pro
100% on-premise zpracování izolované od internetu.

**Prohledávaná báze (RAG)** bude obsahovat zindexovanou Autodesk dokumentaci
(Revit API, Civil 3D objektový model) a manuálně kurátorované repozitáře
otestovaných útržků kódu.

## 4. Uživatelské rozhraní (UI/UX)

Systém musí fungovat jako profesionální vývojářský nástroj, nikoliv jako
generický chatbot.

- **Design systém:** Vizuál postavený na principech HeroUI. Vysoce responzivní,
  optimalizovaný layout.
- **Barevná paleta:** Monochromatická "silver-slate" estetika. Omezení rušivých
  barev na minimum pro soustředěnou práci s kódem.
- **Workspace layout:** Single-page aplikace rozdělená do inteligentních sloupců
  (vlevo kontext a zadání, vpravo Monaco Editor s plným syntax highlightingem).

## 5. Funkční specifikace (Core Features)

| Funkce | Popis a chování |
| --- | --- |
| **Hybridní vstup** | Kromě textového promptu obsahuje nástroj drop-zone pro soubory. Uživatel nahraje strukturu parametrů z Revitu (JSON/CSV) nebo výpis logů. |
| **RAG Generování** | Systém obohatí uživatelský dotaz o relevantní bloky z oficiální Autodesk dokumentace a vygeneruje kód s komentáři. |
| **Debugging Loop** | Dedikované pole pro chybové hlášky. Pokud běh v CADu selže, uživatel vloží stack trace a AI vygeneruje opravu kódu na základě kontextu. |
| **Snippet Library** | Ověřené skripty si uživatel uloží do knihovny a opatří tagy (např. `#Civil3D`, `#pyRevit`). |

## 6. Harmonogram vývoje (Milníky)

- **M1: Prostředí a UI (Týden 1–2):** Konfigurace VPS přes Coolify, nasazení
  Traefik routingu. Příprava základního silver-slate frontendu a editoru.
- **M2: Integrace LLM (Týden 3–4):** Napojení FastAPI na vybrané modely,
  zprovoznění hybridního vstupního pole.
- **M3: RAG Pipeline (Týden 5–6):** Kontejnerizace vektorové databáze, indexace
  Revit API dokumentace a AutoLISP rutin.
- **M4: Debugging a QA (Týden 7–8):** Interní testování na geodetických a
  inženýrských problémech, odladění konzolových vstupů.
