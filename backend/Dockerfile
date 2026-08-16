# syntax=docker/dockerfile:1
FROM python:3.11-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv

# Dependencies are installed from the project metadata alone, so a source-only
# change reuses this layer.
COPY pyproject.toml ./
RUN pip install --no-cache-dir . && pip uninstall -y elys-api

COPY app ./app

# Runs unprivileged: the container has a database password and possibly a model
# API key in its environment.
RUN useradd --system --uid 10001 elys && chown -R elys /srv
USER elys

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/api/health').status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
