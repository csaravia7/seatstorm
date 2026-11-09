FROM python:3.12-slim-bookworm
ARG VERSION=dev
LABEL org.opencontainers.image.title="seatstorm-assistant-agent" \
      org.opencontainers.image.version="${VERSION}" \
      app.kubernetes.io/name="assistant-agent" \
      app.kubernetes.io/part-of="seatstorm"
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 DOCS_DIR=/srv/docs PORT=8080 \
    DT_RELEASE_PRODUCT=seatstorm DT_RELEASE_VERSION=${VERSION}
WORKDIR /srv
COPY services/assistant-agent/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY services/assistant-agent/app ./app
COPY seed/docs ./docs
RUN useradd -r -u 1001 app
USER 1001
EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1"]
