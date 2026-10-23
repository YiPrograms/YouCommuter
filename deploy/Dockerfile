FROM golang:1.27.1-alpine@sha256:8a5910f31396cd4d89662f56c68b3ae31d374308270a1c3bd96672ee5ed43414 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY backend/ backend/
COPY db/ db/
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/server ./backend/cmd/server \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/initdb ./backend/cmd/initdb \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/collect ./backend/cmd/collect \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/healthcheck ./backend/cmd/healthcheck

FROM scratch
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/server /server
COPY --from=build /out/initdb /initdb
COPY --from=build /out/collect /collect
COPY --from=build /out/healthcheck /healthcheck
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/server"]
