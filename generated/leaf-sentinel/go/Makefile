.PHONY: build run-service run-client clean

BINARY := authcallout-leaf-sentinel

build:
	go mod tidy && go build -o $(BINARY) .

run-service: build
	./$(BINARY) service

run-client: build
	./$(BINARY) client $(SEED)

clean:
	rm -f $(BINARY)
