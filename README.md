# ELYS — AI CAD/BIM Script Builder

Generování, ladění a optimalizace skriptů pro AutoCAD, Civil 3D a Revit.
Zadání v [`docs/PRD.md`](docs/PRD.md), odchylky implementace v
[`docs/decisions.md`](docs/decisions.md).

## Stav

Hotový je **M1** (prostředí a UI) a funkční kostra **M2** (napojení na model,
hybridní vstup, debugging loop, knihovna skriptů). **M3** (RAG nad Autodesk
dokumentací) není naimplementovaný — retriever je zapojený, ale vrací prázdno
a hlásí se jako nepřipravený.

| Vrstva | Stav |
| --- | --- |
| Traefik + Docker Compose, Coolify-kompatibilní | hotovo |
| Silver-slate SPA, Monaco Editor (offline) | hotovo |
| FastAPI, streamované generování (SSE) | hotovo |
| Debugging loop (kód + stack trace → oprava) | hotovo |
| Knihovna skriptů s tagy (PostgreSQL) | hotovo |
| Přepínač cloud ↔ on-premise model | hotovo |
| RAG nad Autodesk dokumentací | **chybí (M3)** |
| Autentizace a multi-tenancy | **chybí** |

## Lokální vývoj

Backend:

```bash
cd backend
uv venv --python 3.11 .venv
uv pip install -e ".[dev]"
.venv/bin/uvicorn app.main:app --reload      # http://localhost:8000
```

Běží i bez databáze a bez API klíče: `DATABASE_URL` má výchozí SQLite a bez
klíče se `/api/health` hlásí jako `degraded` — knihovna skriptů funguje dál,
generování vrátí srozumitelnou chybu.

Frontend:

```bash
cd frontend
npm install
npm run dev                                   # http://localhost:5173
```

Vite proxuje `/api` na `localhost:8000`, takže prohlížeč vždy volá stejný origin.

Testy a kontroly:

```bash
cd backend  && .venv/bin/python -m pytest -q
cd frontend && npm run build                  # typecheck + build
```

## Nasazení

```bash
cp .env.example .env    # doplňte ELYS_HOST, ACME_EMAIL, hesla, klíč k modelu
docker compose up -d --build
```

Traefik terminuje TLS a je jediná služba s publikovaným portem. `web` obsluhuje
SPA na kořeni domény, `api` je na stejném hostu pod `/api` — prohlížeč tedy nikdy
nedělá cross-origin požadavek a před streamovanou odpovědí nestojí CORS
preflight. PostgreSQL a Qdrant jsou na interní síti bez přístupu zvenčí.

Na Coolify hostu Traefik už běží — vypusťte službu `traefik` a ponechte jen
labely.

### On-premise režim bez internetu

```bash
docker compose --profile onprem up -d
docker compose exec ollama ollama pull gemma3:12b
# v .env: LLM_PROVIDER=ollama
```

Oba providery implementují stejné rozhraní (`app/llm/base.py`), takže přepnutí
je změna proměnné prostředí. Monaco je zabalené lokálně, takže se nic netahá
z CDN.

## Struktura

```
backend/app/
  api/          FastAPI routery (health, generate, snippets)
  llm/          provider abstrakce — anthropic | ollama
  rag/          retriever seam pro M3
  prompts.py    systémové prompty pro AutoLISP / pyRevit / Dynamo / C#
frontend/src/
  api.ts        klient včetně čtení SSE streamu
  components/   ContextPanel, EditorPane, SnippetLibrary, DropZone
```

## Jak to funguje

Požadavek nese cílové prostředí, zadání, přílohy a — v režimu opravy — kód se
stack tracem. Backend složí systémový prompt podle cíle (pravidla pro
`defun c:` u AutoLISPu, transakce u Revitu…), přidá kontext z retrieveru
a streamuje odpověď modelu po tokenech do editoru.

Generovaný skript **není ověřený**. Nástroj zkracuje cestu k prvnímu funkčnímu
návrhu; spuštění v ostrém výkresu nebo modelu zůstává na inženýrovi. Proto je
knihovna skriptů oddělená — do ní patří až to, co někdo skutečně odzkoušel.
