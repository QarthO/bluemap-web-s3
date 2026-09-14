FROM alpine:3.23 AS webapp
RUN apk add --no-cache curl unzip
WORKDIR /webapp
# Official BlueMap 5.24 release; no frontend modifications.
RUN curl -fsSL https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v5.24/bluemap-5.24-webapp.zip -o /tmp/webapp.zip \
    && echo '5b2ccca1b2a4186da3f7c45023e45f5d0547011e53f4aa68386a30af23652aaa  /tmp/webapp.zip' | sha256sum -c - \
    && unzip /tmp/webapp.zip \
    && rm sql.php

FROM nginx:1.28-alpine
RUN apk add --no-cache jq
COPY --from=webapp /webapp/ /usr/share/nginx/html/
COPY LICENSE.BlueMap /usr/share/nginx/html/LICENSE.BlueMap
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 configure.sh /docker-entrypoint.d/40-bluemap.sh
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/index.html || exit 1
