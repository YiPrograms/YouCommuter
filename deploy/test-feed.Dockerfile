FROM golang:1.27.1-alpine@sha256:8a5910f31396cd4d89662f56c68b3ae31d374308270a1c3bd96672ee5ed43414 AS build
WORKDIR /src
COPY scripts/compose-fixture/main.go main.go
RUN CGO_ENABLED=0 go build -buildvcs=false -trimpath -o /fixture main.go
FROM scratch
COPY --from=build /fixture /fixture
USER 65532:65532
ENTRYPOINT ["/fixture"]
