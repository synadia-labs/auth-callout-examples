FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY go.mod go.sum main.go ./
COPY static ./static
RUN CGO_ENABLED=0 go build -mod=mod -o /demo .

FROM alpine:3.20
RUN apk add --no-cache wget
COPY --from=builder /demo /demo
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/demo"]
