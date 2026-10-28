.PHONY: build run-service run-client clean

BINARY := authcallout-kv-store

build:
	go mod tidy && go build -o $(BINARY) .

run-service: build
	./$(BINARY) service

run-client: build
	./$(BINARY) client $(ARGS)

clean:
	rm -f $(BINARY)
